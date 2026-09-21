package com.drip.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/dripcalc-it.db"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @BeforeAll
    static void cleanDb() throws Exception {
        Files.deleteIfExists(java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "dripcalc-it.db"));
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void indexPageShowsChineseTitle() throws Exception {
        mvc.perform(get("/").characterEncoding("UTF-8"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                                .contains("岩层滴算台")));
    }

    @Test
    void stateHasFixtureData() throws Exception {
        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates.length()").value(4))
                .andExpect(jsonPath("$.hiatuses.length()").value(1))
                .andExpect(jsonPath("$.dates[2].corrGroup").value("G1"));
    }

    @Test
    void runProducesOneBranchCorridorGapAndProxyGrid() throws Exception {
        mvc.perform(post("/api/run")
                        .contentType("application/json")
                        .content("{\"draws\":600,\"seed\":7,\"label\":\"it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.branches.length()").value(1))
                .andExpect(jsonPath("$.result.branches[0].gaps.length()").value(1))
                .andExpect(jsonPath("$.fingerprint", containsString("sha256:")));
    }

    @Test
    void conflictDemoReturnsEvidenceWithTwoBranches() throws Exception {
        mvc.perform(post("/api/demo/conflict")
                        .contentType("application/json")
                        .content("{\"atDateId\":\"D2\",\"ageKa\":9.5,\"ageSigmaKa\":0.22}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.branches.length()").value(2))
                .andExpect(jsonPath("$.result.branches[0].conflicts[0].incompatiblePairs[0].incompatible")
                        .value(true));
    }

    @Test
    void excludingDateChangesActiveCount() throws Exception {
        mvc.perform(post("/api/run").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/dates/D3/excluded?excluded=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excluded").value(true));
        mvc.perform(post("/api/run").contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.result.meta.activeDateCount").value(3));
        mvc.perform(post("/api/dates/D3/excluded?excluded=false")).andExpect(status().isOk());
    }

    @Test
    void exportClearReimportVerifies() throws Exception {
        // 运行一次拿到 id 与导出包
        String runJson = mvc.perform(post("/api/run")
                        .contentType("application/json")
                        .content("{\"draws\":400,\"seed\":42,\"label\":\"export-me\"}"))
                .andReturn().getResponse().getContentAsString();
        Number idNum = com.jayway.jsonpath.JsonPath.read(runJson, "$.id");
        long id = idNum.longValue();

        String export = mvc.perform(get("/api/runs/" + id + "/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"fingerprint\"")))
                .andReturn().getResponse().getContentAsString();

        // 复核接口
        mvc.perform(post("/api/runs/verify").contentType("application/json").content(export))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match").value(true));

        // 清空数据库
        mvc.perform(post("/api/data/clear")).andExpect(status().isOk());
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.dates.length()").value(0));

        // 重新导入导出包（替换数据）并复核
        mvc.perform(post("/api/runs/import?replaceData=true")
                        .contentType("application/json").content(export))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.dates.length()").value(4));

        // 篡改导出包 -> 复核必须失败、拒绝导入
        String tampered = export.replace("\"ageKa\" : 6.5", "\"ageKa\" : 16.5");
        mvc.perform(post("/api/runs/verify").contentType("application/json").content(tampered))
                .andExpect(jsonPath("$.match").value(false));
        mvc.perform(post("/api/runs/import?replaceData=false")
                        .contentType("application/json").content(tampered))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetFixturesRestoresData() throws Exception {
        mvc.perform(post("/api/data/clear")).andExpect(status().isOk());
        mvc.perform(post("/api/fixtures/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dates.length()").value(4));
    }
}
