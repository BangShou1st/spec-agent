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

    public ProfileService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    public UUID getDefaultProfileId() {
        return DEFAULT_PROFILE_ID;
    }

    public Optional<Profile> getDefaultProfile() {
        return profileRepository.findById(DEFAULT_PROFILE_ID);
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
