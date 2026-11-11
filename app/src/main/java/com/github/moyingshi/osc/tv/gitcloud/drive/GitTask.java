package com.github.moyingshi.osc.tv.gitcloud.drive;

public class GitTask {

    public String id;
    public String type;
    public GitTaskState state = GitTaskState.WAITING;
    public int progress;
    public String error;
    public boolean cancelable;
}
