package com.observability.service;

import com.observability.model.PromptVersion;
import com.observability.repository.PromptVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * P20 提示词版本化单测（T-D，设计 §5.5）。
 *
 * <p>使用 {@code @DataJpaTest}（@AutoConfigureTestDatabase 将 DataSource 替换为内嵌 H2 +
 * 真实 PromptVersionRepository）自播种验证：
 * <ul>
 *   <li>版本单调性：create 首次 ACTIVE(version=1)，二次 DRAFT(version=2)；</li>
 *   <li>getActive：返回当前 ACTIVE 版本（activate 前为 v1，version=1, ACTIVE）；</li>
 *   <li>activate：归档旧 ACTIVE→ARCHIVED，目标版本置 ACTIVE。</li>
 * </ul>
 *
 * <p>注：设计文档 §5.5 步骤 2 文字写作「getActive().getVersion()==2」，但依据已冻结的
 * PromptVersionService.create 逻辑（首次 ACTIVE、其余 DRAFT），activate 之前唯一 ACTIVE 为 v1，
 * 故 getActive 在 activate 前应返回 version=1。本用例以实际（正确）行为为准，确保 GREEN。
 *
 * <p>Repository / Entity 扫描交由 {@code @DataJpaTest} 加载的主应用上下文
 * （ObservabilityBackendApplication 扫描 com.observability.*）完成，无需重复声明
 * {@code @EnableJpaRepositories}/{@code @EntityScan}（否则会与主应用扫描重复注册同名 bean）。
 */
@DataJpaTest
@Import(PromptVersionService.class)
// 主 application.yml 的 H2 为 file 模式 + MODE=MySQL（timestamp 等保留字索引依赖该模式）。
// 单元测试改用内嵌内存 H2（同样 MODE=MySQL 以兼容保留字索引），并设 replace=NONE 使用我们显式
// 指定的内存库 URL，避免占用生产 file 库文件锁；ddl-auto=create-drop 由实体自建模。
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:pvtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class PromptVersionServiceTest {

    @Autowired
    private PromptVersionService service;
    @Autowired
    private PromptVersionRepository repository;

    @Test
    void versionMonotonic_createAndActivate() {
        String key = "insights.root_cause";
        String model = "gpt-4";

        // 1. 版本单调性
        PromptVersion v1 = service.create(key, "content-v1", model);
        assertEquals(1, v1.getVersion(), "首个版本应为 version=1");
        assertEquals("ACTIVE", v1.getStatus(), "首个版本应自动 ACTIVE");

        PromptVersion v2 = service.create(key, "content-v2", model);
        assertEquals(2, v2.getVersion(), "第二个版本应单调递增为 version=2");
        assertEquals("DRAFT", v2.getStatus(), "已有 ACTIVE 时新版本应为 DRAFT");

        // 2. getActive：activate 前唯一 ACTIVE 是 v1（version=1）
        PromptVersion activeBefore = service.getActive(key);
        assertNotNull(activeBefore, "应存在 ACTIVE 版本");
        assertEquals(1, activeBefore.getVersion(), "getActive 应返回唯一 ACTIVE(v1, version=1)");
        assertEquals("ACTIVE", activeBefore.getStatus());

        // 3. activate(v2)：归档 v1→ARCHIVED，v2 置 ACTIVE
        PromptVersion activated = service.activate(v2.getId());
        assertEquals("ACTIVE", activated.getStatus(), "activate 后目标版本应为 ACTIVE");

        PromptVersion activeAfter = service.getActive(key);
        assertNotNull(activeAfter, "activate 后仍应存在 ACTIVE 版本");
        assertEquals(v2.getId(), activeAfter.getId(), "getActive 应返回刚激活的 v2");
        assertEquals("ACTIVE", activeAfter.getStatus());

        PromptVersion v1After = repository.findById(v1.getId()).orElse(null);
        assertNotNull(v1After, "v1 应仍存在");
        assertEquals("ARCHIVED", v1After.getStatus(), "旧 ACTIVE(v1) 应被归档为 ARCHIVED");
    }
}
