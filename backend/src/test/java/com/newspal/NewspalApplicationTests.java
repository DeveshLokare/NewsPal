package com.newspal;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class NewspalApplicationTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void migrationsCreateSchemaAndSeedCategories() {
		Integer categories = this.jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class);
		assertThat(categories).isEqualTo(8);
		Integer tables = this.jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name IN
				('users','categories','user_categories','articles','archived_articles','api_usage','job_runs')""",
				Integer.class);
		assertThat(tables).isEqualTo(7);
	}

}
