package com.cenoffice.workspace;

public final class WsEnums {
    private WsEnums() {}

    public enum AgentRole { PM, DEV, QA }

    /** 실행 환경. AI 에이전트의 작업 범위는 서버에서 환경별로 제한한다. */
    public enum Environment { DEV, STG, PROD }
}
