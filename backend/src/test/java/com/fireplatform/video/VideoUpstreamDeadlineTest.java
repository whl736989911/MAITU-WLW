package com.fireplatform.video;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import org.junit.jupiter.api.Test;

class VideoUpstreamDeadlineTest {
    @Test
    void expiredWholeExchangeDeadlineRejectsEvenWhenUnderlyingStreamHasData() {
        var stream = new VideoUpstreamClient.DeadlineInputStream(new ByteArrayInputStream(new byte[]{1}),
            new Socket(), System.nanoTime() - 1, 1000);

        assertThatThrownBy(stream::read).isInstanceOf(java.io.IOException.class);
    }
}
