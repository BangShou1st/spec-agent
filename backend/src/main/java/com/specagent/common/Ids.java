package com.specagent.common;

import java.util.UUID;

/**
 * 文件名:Ids.java
 *
 * 用途:运行时记录的标识符生成工具,统一从这里取随机 UUID。
 *
 * 使用随机 UUID,使持久化记录无需依赖数据库序列即可获得稳定、
 * 唯一的身份标识。
 */
public final class Ids {

    private Ids() {
    }

    public static UUID random() {
        return UUID.randomUUID();
    }
}
