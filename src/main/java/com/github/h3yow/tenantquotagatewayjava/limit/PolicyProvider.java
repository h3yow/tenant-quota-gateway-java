package com.github.h3yow.tenantquotagatewayjava.limit;

/**
 * 策略来源。实现方负责在底层配置变化时产出新的不可变快照。
 */
public interface PolicyProvider {

    /**
     * 返回当前生效的策略快照。
     *
     * @throws IllegalStateException 当配置不可用时
     */
    PolicySnapshot currentSnapshot();

    /**
     * 主动从底层配置重新加载。
     *
     * @return 加载后的新快照
     * @throws IllegalStateException 当配置不可用或内容非法时
     */
    PolicySnapshot reload();
}
