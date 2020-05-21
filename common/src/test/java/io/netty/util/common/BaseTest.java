package io.netty.util.common;

import io.netty.util.internal.PlatformDependent;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

@Slf4j
public class BaseTest {

    @Test
    public void test1() {

        log.info(System.getProperty("os.name"));

        log.info(System.getProperty("user.name"));

        log.info(System.getProperty("java.vm.name"));

        log.info("{}", PlatformDependent.isOsx());
    }
}
