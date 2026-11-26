package com.cenoffice.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DemoAccountsTest {
    @Test
    void onlyListedAccountsGetTheWideLimit() {
        DemoAccounts d = new DemoAccounts(" demo , guest ");
        assertThat(d.isDemo("demo")).isTrue();
        assertThat(d.isDemo("guest")).isTrue();
        assertThat(d.isDemo("admin")).isFalse();
        assertThat(d.isDemo(null)).isFalse();
        assertThat(d.limit("demo", 10)).isEqualTo(DemoAccounts.PER_MINUTE);
        assertThat(d.limit("admin", 10)).isEqualTo(10);
    }

    @Test
    void emptyConfigMeansNoDemoAccount() {
        DemoAccounts d = new DemoAccounts("");
        assertThat(d.isDemo("demo")).isFalse();
        assertThat(d.limit("demo", 20)).isEqualTo(20);
    }
}
