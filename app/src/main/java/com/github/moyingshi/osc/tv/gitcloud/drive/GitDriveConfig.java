package com.github.moyingshi.osc.tv.gitcloud.drive;

import com.github.moyingshi.osc.tv.gitcloud.GitAccount;
import com.github.moyingshi.osc.tv.gitcloud.GitRepo;

import java.io.File;

public class GitDriveConfig {

    public GitAccount account;
    public GitRepo repo;
    public String token;
    public String branch;
    public File worktreeDir;
    public String purpose;
    public String defaultRemotePath;

    public String branch() {
        return branch == null || branch.isEmpty() ? repo.defaultBranch : branch;
    }
}
