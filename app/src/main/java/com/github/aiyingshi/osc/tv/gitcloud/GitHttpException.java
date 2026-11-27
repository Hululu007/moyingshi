package com.github.aiyingshi.osc.tv.gitcloud;

public class GitHttpException extends GitCloudException {

    public final int code;

    public GitHttpException(int code, String message) {
        super(message);
        this.code = code;
    }
}
