package com.rcdis.agent.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashUtilsTests {

    @Test
    void sha256HexReturnsExpectedDigest() {
        String digest = HashUtils.sha256Hex("abc");

        assertThat(digest).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
