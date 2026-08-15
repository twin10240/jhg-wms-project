package com.jhg.wms.config;

/** 원장에 남길 행위자. 사람이면 사용자명, 서버간 호출이면 서비스 계정명, 그 외는 "system". */
public interface ActorProvider {
    String current();
}
