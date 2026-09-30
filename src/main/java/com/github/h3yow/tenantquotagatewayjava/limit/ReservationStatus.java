package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 一笔配额预占的生命周期状态。
 */
public enum ReservationStatus {

    /** 已占用额度，等待业务结算；占用量在结算或超时前计入窗口已用量。 */
    ACTIVE,

    /** 已按真实用量结算完成（多退少补已生效），终态，任何重复结算不再生效。 */
    SETTLED,

    /** 预占超时未结算而被自动释放（终态），占用量已退回，迟到结算不再生效。 */
    EXPIRED
}
