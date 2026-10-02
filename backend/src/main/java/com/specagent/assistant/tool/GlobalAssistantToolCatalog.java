package com.specagent.assistant.tool;

import com.specagent.capability.CapabilityQueryContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:GlobalAssistantToolCatalog.java
 *
 * 用途:全局助手 V1 的产品能力边界——显式白名单圈定模型可以调用的
 * 工具集合,叠加 APPLICATION:GLOBAL_ASSISTANT 上下文标记形成双重隔离。
 * 它是决策校验器、目录服务和各能力实现共同引用的"允许什么"的依据。
 */
public final class GlobalAssistantToolCatalog {
    private GlobalAssistantToolCatalog() {
    }
    public static final String SUPPORT_MARKER = "APPLICATION:GLOBAL_ASSISTANT";
    public static final List<String> TOOL_IDS = List.of(
            ProjectCreateCapability.CAPABILITY_ID,
            ProjectSearchCapability.CAPABILITY_ID,
            ProjectListRecentCapability.CAPABILITY_ID,
            ProjectGetSummaryCapability.CAPABILITY_ID,
            SkillImportCapability.CAPABILITY_ID,
            SkillDiscoverCapability.CAPABILITY_ID);
    public static final String FINGERPRINT = "ga-v1:project.create,project.search,"
            + "project.list_recent,project.get_summary,skill.import,skill.import.discover";
    public static boolean isAllowed(String capabilityId) {
        return TOOL_IDS.contains(capabilityId) || Set.of("help.search","project.content.discover").contains(capabilityId);
    }
    /**
     * V1 各工具的精确参数契约。未知键一律被决策校验器拒绝;
     * 适配器再套同一份允许名单作为防御性的第二道闸,但不重复值校验。
     */
    public static java.util.Set<String> allowedArguments(String capabilityId) {
        if (ProjectCreateCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("title");
        }
        if (ProjectSearchCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("query", "limit");
        }
        if (ProjectListRecentCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("limit");
        }
        if (ProjectGetSummaryCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("projectId");
        }
        // skill.import 暂存一条可审阅的导入记录;它不是 Skill Runtime 宿主工具
        // (skill.activate / skill.search / skill.read_resource)之一,
        // 后者必须对面向模型的 GA 目录保持不可达。
        if (SkillImportCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("url", "ref", "skill");
        }
        if (SkillDiscoverCapability.CAPABILITY_ID.equals(capabilityId)) {
            return java.util.Set.of("url", "ref");
        }
        if("help.search".equals(capabilityId)) return Set.of("query","limit");
        if("project.content.discover".equals(capabilityId)) return Set.of("query","projectId","limit");
        return null;
    }
    /**
     * 针对 Jackson 会产出的 JDK 数值类型做通用整数校验。
     * 只有 [1,10] 区间内的真整数才通过;小数值、数字字符串、布尔值
     * 一律不通过,不管它们的 intValue 是多少。
     */
    public static boolean isValidLimit(Object raw) {
        if (raw == null) {
            return true;
        }
        long value;
        if (raw instanceof Integer number) {
            value = number;
        } else if (raw instanceof Long number) {
            value = number;
        } else if (raw instanceof Short number) {
            value = number;
        } else if (raw instanceof Byte number) {
            value = number;
        } else if (raw instanceof java.math.BigInteger number) {
            return number.compareTo(java.math.BigInteger.ONE) >= 0
                    && number.compareTo(java.math.BigInteger.TEN) <= 0;
        } else {
            return false;
        }
        return value >= 1 && value <= 10;
    }
    public static int limitOrDefault(Object raw, int fallback) {
        if (raw instanceof Integer number) {
            return number;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }
    public static CapabilityQueryContext queryContext(Set<String> grantedPermissions) {
        return new CapabilityQueryContext(
                grantedPermissions == null ? Set.of() : grantedPermissions,
                List.of(SUPPORT_MARKER),
                Map.of());
    }
    public static CapabilityQueryContext queryContext() {
        return queryContext(Set.of());
    }
}
