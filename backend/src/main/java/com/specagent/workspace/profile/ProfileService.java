package com.specagent.workspace.profile;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:ProfileService.java
 *
 * 用途:读取通用需求画像。画像是配置而不是代码,绝不允许引入
 * 运行时的领域特定分支。默认画像由数据库迁移脚本播种。
 */
@Service
public class ProfileService {

    public static final UUID DEFAULT_PROFILE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final ProfileRepository profileRepository;
    private com.specagent.access.OrganizationConfigurationScope scope;
    @org.springframework.beans.factory.annotation.Autowired
    public void setOrganizationScope(com.specagent.access.OrganizationConfigurationScope scope) {this.scope=scope;}

    public ProfileService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    public UUID getDefaultProfileId() {
        return DEFAULT_PROFILE_ID;
    }

    /**
     * 组织内的默认画像 id。
     *
     * 项目创建必须用它而不是 {@link #getDefaultProfileId()}:冻结决定 #2 要求
     * {@code project.default_profile_id} 引用<b>同组织</b>画像,并由复合外键
     * 在数据库侧强制。对本地/存量组织,解析结果就是播种的模板行,单机行为不变;
     * 对新组织,首次解析会复制一份组织内的画像。
     */
    public UUID getDefaultProfileIdForOrg(UUID orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException("An organization is required for a default profile");
        }
        return profileRepository.ensureOrgDefaultProfile(orgId, DEFAULT_PROFILE_ID);
    }

    public Optional<Profile> getDefaultProfile() {
        return profileRepository.findById(getDefaultProfileIdForOrg(scope.readOrg()));
    }

    public Optional<Profile> getProfile(UUID id) {
        return profileRepository.findById(id);
    }

    public Optional<Profile> findByName(String name) {
        return profileRepository.findByName(name);
    }

    public List<Profile> listProfiles() {
        return profileRepository.findAll();
    }
}
