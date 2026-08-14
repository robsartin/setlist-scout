package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class AppCssServedTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @org.junit.jupiter.api.BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    @Test
    void appCssIsServedWithBothThemes() throws Exception {
        mvc.perform(get("/css/app.css"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith("text/css"))
           .andExpect(content().string(containsString(":root")))
           .andExpect(content().string(containsString("prefers-color-scheme: dark")))
           .andExpect(content().string(containsString("--primary")))
           .andExpect(content().string(containsString("--tribute-ink")));
    }
}
