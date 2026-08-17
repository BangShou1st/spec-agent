package com.specagent.profile;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads generic requirement profiles.
 *
 * <p>A profile is configuration, not code. It must never introduce runtime
 * domain-specific branches. The default profile is seeded by migration.
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
