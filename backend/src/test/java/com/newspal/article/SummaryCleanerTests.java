package com.newspal.article;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cases taken from live Free News API summaries (2026-10-24, trimmed).
 */
class SummaryCleanerTests {

	private static final String TITLE = "Some unrelated headline about the news";

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			Skip to contentAllNewsSportCultureLifestyle New Zealand's Glenn Phillips dives for the crease | New Zealand's Glenn Phillips dives for the crease
			Skip to mainSat, October 24, 2026 at 8:30 AM UTCA friend of Meghan has told of relief | A friend of Meghan has told of relief
			Open accessibility guideSkip to contentSkip to footerAdvertisement The airport finally opens to passengers | The airport finally opens to passengers
			LifeWellbeingAn intense four-day programme is delivering early results | An intense four-day programme is delivering early results
			Home PhonesNews Great hardware, but Safari still needs room to breathe | Great hardware, but Safari still needs room to breathe
			NEWYou can now listen to Fox News articles! Virginia Democrats are urging restraint | Virginia Democrats are urging restraint
			Key events7m agoPreamblePreambleYou remember Manchester City, don't you? | You remember Manchester City, don't you?
			SummaryPakistani actress Aymen Saleem has officially joined the party | Pakistani actress Aymen Saleem has officially joined the party
			Table of Contents In our world of rising tech prices, is it worth it? | In our world of rising tech prices, is it worth it?
			Published 08:00 24 Oct 2026 GMT+1Skyrim gamers have a promising new RPG to try | Skyrim gamers have a promising new RPG to try
			Published 26 seconds agoon October 24, 2026A remarkable series of photographs reveals a coral | A remarkable series of photographs reveals a coral
			Updated / Saturday, 24 Oct 2026 08:00 Kira warns Orla about Danny on Sunday | Kira warns Orla about Danny on Sunday
			'News|Articles|October 24, 2026 Get caught up with the latest coverage from the meeting' |Get caught up with the latest coverage from the meeting
			October 2026 13:03 (UTC+04:00) Cryptocurrency prices continued to decline | Cryptocurrency prices continued to decline
			Oct 24, 2026 5:00 AMThe Webb telescope sees tiny red dots everywhere | The Webb telescope sees tiny red dots everywhere
			By LAURA FOX, ASSOCIATE SHOWBUSINESS EDITOR Published: 09:57 BST, 24 October 2026 In a night when the nation stayed home | In a night when the nation stayed home
			By Dipo Olowookere The bulls resurfaced to the exchange on Friday | The bulls resurfaced to the exchange on Friday
			Picture Credit: XBollywood veteran Anupam Kher wished Prabhas a happy birthday | Bollywood veteran Anupam Kher wished Prabhas a happy birthday
			Image source, Getty ImagesImage caption, For everyone's eyes only - three actors are in the frame | For everyone's eyes only - three actors are in the frame
			Stock photo: Dimitri Karastelev/Unsplash. MOST people have sent a message they later regretted | MOST people have sent a message they later regretted
			Photo by Alex Pugliese / Unsplash The big trade was yesterday and it was huge | The big trade was yesterday and it was huge
			Open this photo in gallery:A pedestrian passes a campaign billboard in Tel Aviv | A pedestrian passes a campaign billboard in Tel Aviv
			Prefer HTon GoogleWhat causes your feet to feel colder than the rest of your body | What causes your feet to feel colder than the rest of your body
			Firefighting is underway at the scene. Prefer HTon Google A massive fire broke out | Firefighting is underway at the scene. A massive fire broke out
			""")
	void stripsLeadingClutter(String scraped, String expected) {
		assertThat(SummaryCleaner.clean(scraped, TITLE)).isEqualTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"To use this website, cookies must be enabled in your browser. To enable cookies, follow the instructions",
			"Live Saturday 24 October 2026 11:30, UK This content is provided by , which may be using cookies and other technologies.",
			"Listen to article 3 min listen Audio reading is not supported on this...",
			"The No. 1 source of news for every West AustralianNo lock-in contractDigital$8$4Save $32!",
			"Skip to content Advertisement" })
	void noticesAndPureClutterLeaveNoSummary(String scraped) {
		assertThat(SummaryCleaner.clean(scraped, TITLE)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Live music returns to the city's parks this weekend after a long break.",
			"Saturday's match was postponed after heavy rain flooded the pitch overnight.",
			"PlayStation Plus subscribers get three new games in November, Sony said.",
			"YouTube star MrBeast announced a new show on Friday, his biggest yet.",
			"JerryRigEverything's durability test left the foldable with deep scratches.",
			"By the end of the week, the storm is expected to have moved out to sea.",
			"McDonald's is bringing back a menu favourite for a limited time this winter.",
			"MATCH REPORT: Fiji edged Japan 34-28 in Tokyo on Saturday to give the coach a win.",
			"New Zealand recovered from an early collapse to post 180 for five.",
			"October's budget announcement saw the official introduction of a new account.",
			"Days after the vote, the party is still counting ballots in two districts." })
	void leavesOrdinaryTextAlone(String text) {
		assertThat(SummaryCleaner.clean(text, TITLE)).isEqualTo(text);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			Want To Protect Your Muscle As You Age? Start With This Checklist | Want To Protect Your Muscle As You Age? Start With This ChecklistThink about all the little things your muscles do | Think about all the little things your muscles do
			Consult experts before banning Waze, DAP's Yeo urges govt | Consult experts before banning Waze, DAP's Yeo urges govt24-Oct-2026, 17:0024 Oct 2026, 17:00The ex-minister questions the ban | The ex-minister questions the ban
			Golden Nuggets: no more slop bowls | Story byGolden Nuggets: no more slop bowlsATLANTA, GA OCTOBER 16: San Francisco tight end runs | ATLANTA, GA OCTOBER 16: San Francisco tight end runs
			""")
	void stripsARepeatedTitle(String title, String scraped, String expected) {
		assertThat(SummaryCleaner.clean(scraped, title)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			Anti-SIR protest LIVE: 'Not questioning the court' | Anti-SIR protest LIVE: CJP leaders have vowed to continue their movement
			Short title | Short title is repeated but too short to trust as a title match here
			""")
	void keepsTextThatOnlyResemblesTheTitle(String title, String text) {
		assertThat(SummaryCleaner.clean(text, title)).isEqualTo(text);
	}

}
