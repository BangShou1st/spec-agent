# 集中代码审查

审查对象为本轮生产适配器、真实 worker/管理状态更新、V103、现有 runtime/registry/continuation
的最小接缝及测试。没有重审 V99/V100/V101；仅核对旧迁移无差异、版本下一个为 V103。

已处理的重点问题：

1. retry 复用 generation ID；不能把每批递增的 lease epoch 当业务代次。新增 readiness_revision，
   仅显式重试递增，持久化 outcome 和 binding 都用同一复合身份。
2. 单纯 afterCommit 通知会丢事件。真实终态事务同时落 outcome，已有 continuation 轮询重扫；
   依赖未注册、暂时无法恢复、实例退出不删除完成事实。
3. 静态 deferred adapter 原来没有 provider 级的结果授权钩子。registry 现对静态 deferred
   重放/观察也调用已有授权契约；同步 adapter 不变。读取持久化用户委托，完成重新校验。
4. 原 fail 方法宽泛捕获 IllegalStateException，会把新终态事实写入失败当作陈旧租约而吞掉。
   现在只有 owned(token) 的拒绝是 no-op，failRow 及事件存储异常必须逃逸并回滚。
5. 固定最老候选会被持续撤权或故障项占满。bounded 扫描持久化轮转时间并 SKIP LOCKED，
   各候选独立事务；失败不妨碍其他候选及既有 timeout/outbox 恢复。
6. 内部 completeDeferred 也必须核对真实源终态及完整结果身份。新增兼容的 validateCompletion
   默认钩子，生产适配器拒绝尚未完成、旧 generation、旧 revision 或伪造结果。
   临时移除该生产校验的反例运行 17 项中恰好 3 项失败；恢复后最终完整回归通过。
   这是新增实现的回归变异验证，不声称旧基线已有该生产适配器缺陷。
7. 绑定 INSERT SELECT 再校验调用状态、capability、真实任务、project/org/revision；
   模型无参数，无任意外部 ID。未放宽 ActionEligibility、Schema required、绑定摘要或引用检查。

状态语义审查：真实业务没有运行中取消重建接口；没有添加假 CANCELLED。READY 后 discard
是放弃激活，首次 readiness 事实不倒退。历史 DISCARDED 事实映射 FAILED。取消 Run、撤权、
无任务、缺失绑定等走现有拒绝/超时/续跑资格门禁，不能由离线 mock 推论真实数据库行为。

本轮静态审查没有剩余已确认但未修复的 P0/P1。仍有明确发布阻断：V103 真正执行、旧迁移
验收、真实提交可见性/并发锁/时钟竞争、多进程恢复、完整 Spring 装配和真实检索任务 E2E。
旧 writer 不递增 revision/不写 outcome，不能混跑，必须排空后统一升级。

保护复核：原有 627 个修改/未跟踪路径 SHA256 或删除状态均未改变。main 未移动，PR #19
未操作，没有生产 Prompt 修改。C03 REJECT 保留；只在 dev 显式路径暂存、本地提交。
