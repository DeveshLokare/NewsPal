-- Tuned against the live webz.io /api/news on 2026-10-18:
--  * webz.io labels are "Sport" (not "Sports") and "War, Conflict and Unrest".
--  * site_type:news had no measurable effect; video and blog hosts that slip
--    through domain_rank (no summary text) are excluded by site instead.
--  * domain_rank:<5000 drops low-quality and press-release sites; there are tens of
--    thousands of matches per category every 3 days, so we can afford to be picky.
UPDATE categories SET provider_query = 'category:"Science and Technology" language:english domain_rank:<5000 -site:dailymotion.com -site:tumblr.com' WHERE slug = 'technology';
UPDATE categories SET provider_query = 'category:"Economy, Business and Finance" language:english domain_rank:<5000 -site:dailymotion.com -site:tumblr.com' WHERE slug = 'business';
UPDATE categories SET provider_query = 'category:Sport language:english domain_rank:<5000 -site:dailymotion.com -site:tumblr.com' WHERE slug = 'sports';
UPDATE categories SET provider_query = 'category:"Science and Technology" (research OR study OR scientists OR space) language:english domain_rank:<5000 -site:dailymotion.com -site:tumblr.com' WHERE slug = 'science';
UPDATE categories SET provider_query = 'category:Health language:english domain_rank:<5000 -site:dailymotion.com -site:tumblr.com' WHERE slug = 'health';
UPDATE categories SET provider_query = 'category:"Arts, Culture and Entertainment" language:english domain_rank:<5000 -site:dailymotion.com -site:tumblr.com' WHERE slug = 'entertainment';
UPDATE categories SET provider_query = 'category:"War, Conflict and Unrest" language:english domain_rank:<5000 -site:dailymotion.com -site:tumblr.com' WHERE slug = 'world';
UPDATE categories SET provider_query = 'category:Politics language:english domain_rank:<5000 -site:dailymotion.com -site:tumblr.com' WHERE slug = 'politics';
