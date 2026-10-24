package com.newspal.search;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
			tesl                        | ''                      | tesl
			'  elon musk '              | elon                    | musk
			Écoles                      | ''                      | écoles
			'"elon musk"'               | '"elon musk"'           | NULL
			'"elon mu'                  | '"elon mu'              | NULL
			apple -samsung              | apple -samsung          | NULL
			apple or samsung            | apple or samsung        | NULL
			'iphone 17'                 | iphone                  | 17
			'tesla!'                    | 'tesla!'                | NULL
			a                           | a                       | NULL
			""")
	void splitsALastPlainWordIntoAPrefix(String text, String websearch, String prefix) {
		SearchQuery query = SearchQuery.parse(text);
		assertThat(query.websearch()).isEqualTo(websearch);
		assertThat(query.prefix()).isEqualTo(prefix);
	}

}
