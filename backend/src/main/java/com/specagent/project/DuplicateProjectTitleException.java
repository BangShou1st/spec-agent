package com.specagent.project;

/**
 * Raised when a project title is already used by another project.
 *
 * <p>Titles are compared case-insensitively, matching the semantics of the
 * title search in {@code ProjectRepository#findByTitleContaining}. Deleting a
 * project frees its title: uniqueness is a property of the projects that
 * currently exist, never of the titles ever used.
 */
public class DuplicateProjectTitleException extends RuntimeException {

    public DuplicateProjectTitleException(String title) {
        super("Project title already exists: " + title);
    }
}
