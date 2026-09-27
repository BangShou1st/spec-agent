package com.specagent.agent.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 文件名:ExecutionFence.java
 *
 * 用途:执行器所有权 fencing 的统一入口,第四轮复核 R4-A/R4-C 的闭合。
 *
 * <strong>为什么单条条件 UPDATE 不够(R4-A 的教训)。</strong>条件 UPDATE
 * 里的 {@code (SELECT epoch FROM executor_ownership ...) = :ownerEpoch}
 * 子查询读取的是<strong>语句快照</strong>(Read Committed):UPDATE 在
 * PostgreSQL 内等待目标行锁期间,另一个连接仍可以提交所有权代次递增,
 * 旧 UPDATE 恢复后依旧按开始时的快照通过条件并提交——独立复核的反例
 * (SnapshotFenceHarness)证明了这一点。语句内快照子查询无法建立"业务
 * 事务提交"与"接管提交"之间的互斥。
 *
 * <strong>事务协议:真实互斥靠锁,立即拒绝靠丢锁闩锁。</strong>
 * <ol>
 *   <li><strong>接管互斥(R4-A)。</strong>一切受保护的业务写入事务在
 *       事务内、任何业务行锁之前,通过<strong>同一事务绑定的连接</strong>
 *       对 {@code executor_ownership} 单行取 {@code FOR SHARE} 锁并验证
 *       代次({@link #lockOwnershipForWrite()})。锁保持到整个业务事务
 *       提交或回滚;接管的 epoch 递增(ON CONFLICT DO UPDATE 的行排他锁)
 *       与 FOR SHARE 冲突——于是只有两种可接受顺序:
 *       <ul>
 *         <li><strong>顺序 A</strong>:旧事务先完成,接管等待它结束后才
 *             能提交新代次;旧事务不会读到新代次之后的任何状态;</li>
 *         <li><strong>顺序 B</strong>:接管先提交,旧事务的 FOR SHARE
 *             读取(取锁时读最新已提交版本)看到新代次,验证失败,整个
 *             业务事务(含未提交产物)立即拒绝回滚。</li>
 *       </ul>
 *       禁止的结果——新代次已提交、旧事务仍按旧快照成功提交——在两种
 *       顺序下都不可能出现。</li>
 *   <li><strong>已知丢锁立即拒绝(R4-C)。</strong>{@link #ownerEpoch()}
 *       不再无条件返回租约对象的旧代次:租约一旦被判定永久丢失
 *       (isLost 闩锁),一切检查点/终态/认领写入入口立即抛出
 *       {@link ExecutorLease.LeaseLostException}——数据库代次比较代替
 *       不了"本进程已知丢锁"这一事实(尚无新执行器接管时全局代次未变,
 *       但本进程已无权写入)。</li>
 *   <li><strong>锁顺序。</strong>所有权行锁先于项目/任务/业务行锁获取;
 *       FOR SHARE 之间互相兼容(含 REQUIRES_NEW 失败终态化在同一外层
 *       事务持锁时再取共享锁),与接管的行排他锁冲突——不构成任何
 *       等待环。</li>
 * </ol>
 *
 * <strong>无租约进程(偏置为 0)。</strong>没有执行器租约的进程
 * (API-only、测试驱动)自动放行:ownerEpoch() 返回 0,仓储层对这些写入
 * 保持历史行为。持有租约的进程绝不因租约缺失/丢失退化为 0——租约构造
 * 失败会让启动直接失败,丢失则由闩锁永久拒绝。
 */
@Component
public class ExecutionFence {

    private final ObjectProvider<ExecutorLease> lease;
    private final NamedParameterJdbcTemplate jdbc;

    public ExecutionFence(ObjectProvider<ExecutorLease> lease,
                          NamedParameterJdbcTemplate jdbc) {
        this.lease = lease;
        this.jdbc = jdbc;
    }

    /** 所有权敏感写入前调用;丢失即抛出,调用方事务据此回滚。 */
    public void assertOwnership() {
        ExecutorLease executorLease = lease.getIfAvailable();
        if (executorLease != null) {
            executorLease.assertOwned();
        }
    }

    /**
     * 本进程写入 run 行时应携带的所有权代次(fencing token)。
     *
     * 返回 0 表示本进程没有执行器租约(API-only、测试驱动):不存在执行器
     * 之争,仓储层对这类写入保持历史行为。持有租约时返回租约代次——
     * <strong>租约已被判定永久丢失时抛出 {@link ExecutorLease.LeaseLostException}</strong>
     * (R4-C 的闭合):数据库 epoch 比较探测不到"尚无人接管的丢锁"
     * (全局代次未变),必须由丢锁闩锁立即拒绝。持有租约但未丢失时,仓储
     * 层把它写进认领条件,并在一切 run 状态写入的同一条 UPDATE 里验证。
     */
    public long ownerEpoch() {
        ExecutorLease executorLease = lease.getIfAvailable();
        if (executorLease == null) {
            return 0L;
        }
        if (executorLease.isLost()) {
            throw new ExecutorLease.LeaseLostException(executorLease.getLostReason());
        }
        return executorLease.epoch();
    }

    /** 本进程是否持有执行器租约(无论是否已丢失)。 */
    public boolean hasLease() {
        return lease.getIfAvailable() != null;
    }

    /** 租约是否存在且已被判定永久丢失(无租约进程返回 false)。 */
    public boolean leaseLost() {
        ExecutorLease executorLease = lease.getIfAvailable();
        return executorLease != null && executorLease.isLost();
    }

    /**
     * 受保护写入事务的入口锁:在<strong>当前事务</strong>(事务绑定的同一
     * 连接)内对 {@code executor_ownership} 单行取 {@code FOR SHARE} 并验证
     * 全局代次仍等于本执行器代次,返回验证通过的代次(无租约进程返回 0)。
     *
     * <p>必须在业务事务的<strong>第一条数据库语句</strong>处调用(先于一切
     * 项目/任务/业务行锁与业务写入),锁由数据库保持到事务提交或回滚。
     * 接管的代次递增与本锁冲突,从而建立 R4-A 要求的提交互斥:
     * FOR SHARE 验证通过后,接管在本事务结束前不可能提交;接管已提交的
     * 情况下,本方法取锁时读到的就是新代次,验证失败、调用方整体拒绝。
     *
     * <p>FOR SHARE 读取的是取锁时的最新已提交版本(PostgreSQL 锁等待
     * 结束后返回更新后的元组),因此验证不会基于过期快照。取锁后复查
     * 一次丢锁闩锁:租约会话可能在等待行锁期间被终止并被其他线程判失。
     *
     * @return 验证通过的所有权代次;0 表示无租约(不取锁,历史行为)
     * @throws ExecutorLease.LeaseLostException 已知丢锁,或全局代次已易主
     */
    public long lockOwnershipForWrite() {
        ExecutorLease executorLease = lease.getIfAvailable();
        if (executorLease == null) {
            return 0L;
        }
        if (executorLease.isLost()) {
            throw new ExecutorLease.LeaseLostException(executorLease.getLostReason());
        }
        long expected = executorLease.epoch();
        // FOR SHARE:与接管的行排他锁(ON CONFLICT DO UPDATE)互斥;多个
        // 业务事务/REQUIRES_NEW 失败终态化之间共享兼容,不构成自等待。
        // 同一事务内重复取 FOR SHARE 是安全的(锁计数)。
        Long currentEpoch = jdbc.getJdbcTemplate().queryForObject(
                "SELECT epoch FROM executor_ownership WHERE id = 1 FOR SHARE",
                Long.class);
        if (currentEpoch == null || currentEpoch != expected) {
            throw new ExecutorLease.LeaseLostException(
                    "executor_ownership epoch moved from " + expected
                            + " to " + currentEpoch + "; another executor has taken over");
        }
        if (executorLease.isLost()) {
            throw new ExecutorLease.LeaseLostException(executorLease.getLostReason());
        }
        return expected;
    }
}
