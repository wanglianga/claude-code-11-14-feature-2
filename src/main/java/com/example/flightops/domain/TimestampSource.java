package com.example.flightops.domain;

/** 口径时间戳来源：机场看推出、航司看起飞（或到达）、集团按到达；两侧时间戳来源不同是差额根源。 */
public enum TimestampSource {
    PUSHBACK,   // 推出时刻（机场放行视角）
    DEPARTURE,  // 实际起飞时刻（航司视角）
    ARRIVAL     // 实际到达时刻（集团报送视角）
}
