package com.sentinel.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环境数和容器数上限的边界。
 */
@DisplayName("供给上限判断")
class ProvisioningLimitDeciderTest {

    @Test
    @DisplayName("等于上限可以启动，再多一个不行")
    void environmentLimitIsInclusive() {
        assertThat(ProvisioningLimitDecider.exceeds(9, 0, 0, 10, 30)).isFalse();
        assertThat(ProvisioningLimitDecider.exceeds(10, 0, 0, 10, 30)).isTrue();
    }

    @Test
    @DisplayName("容器数加上本次需要的数量，超过上限才拒绝")
    void containerLimitIncludesWhatTheTaskNeeds() {
        assertThat(ProvisioningLimitDecider.exceeds(0, 28, 2, 10, 30)).isFalse();
        assertThat(ProvisioningLimitDecider.exceeds(0, 29, 2, 10, 30)).isTrue();
        assertThat(ProvisioningLimitDecider.exceeds(0, 30, 0, 10, 30)).isFalse();
        assertThat(ProvisioningLimitDecider.exceeds(0, 30, 1, 10, 30)).isTrue();
    }

    @Test
    @DisplayName("需要的容器数为负时按 0 计，仍检查环境上限")
    void negativeNeedCountsAsZero() {
        assertThat(ProvisioningLimitDecider.exceeds(0, 30, -3, 10, 30)).isFalse();
        assertThat(ProvisioningLimitDecider.exceeds(10, 0, -1, 10, 30)).isTrue();
    }

    @Test
    @DisplayName("0 或负数表示该项不限制")
    void zeroOrNegativeMeansUnlimited() {
        assertThat(ProvisioningLimitDecider.exceeds(100, 100, 5, 0, 0)).isFalse();
        assertThat(ProvisioningLimitDecider.exceeds(100, 100, 5, -1, -4)).isFalse();
        assertThat(ProvisioningLimitDecider.exceeds(10, 0, 1, 0, 30)).isFalse();
        assertThat(ProvisioningLimitDecider.exceeds(0, 30, 1, 10, 0)).isFalse();
        assertThat(ProvisioningLimitDecider.exceeds(0, 29, 2, -5, 30)).isTrue();
    }
}
