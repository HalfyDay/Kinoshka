---
source_url: "https://shikimori.one/api/doc"
type: webpage
title: "Shikimori API"
captured_at: 2026-08-26T00:31:22.287965+00:00
contributor: "unknown"
---

# Shikimori API

Source: https://shikimori.one/api/doc

---

Shikimori API Shikimori API 2.0 1.0 / 2.0 Welcome to Shikimori API v2 The API has three versions: graphql , outdated v2 and outdated v1 . Prefer using graphql over v2 / v1 when it is possible. Please do not parse the main site . Fetch all necessary data via API. NOTE: New anime/manga/character/person posters available only in graphql API. API works with HTTPS protocol only. Documentation for GraphQL Click here . Documentation for V1 Click here . Documentation for V2 On this page below. Authentication OAuth2 is used for authentication. OAuth2 guide . All other auth methods are deprecated and will be removed after 2018-07-01. Restrictions API access is limited by 5rps and 90rpm Requirements Add your Oauth2 Application name to User-Agent requests header. Don’t mimic a browser. Your IP address may be banned if you use API without properly set User-Agent header. Third party implementations Python API implementation by OlegWock. Node.js API implementation by Capster. C# API implementation by JustRoxy. Ruby API implementation by iwdt. Feedback @morr , email Ресурсы Topic ignore Ресурс Описание POST /api/v2/topics/:topic_id/ignore Ignore a topic DELETE /api/v2/topics/:topic_id/ignore Unignore a topic User ignore Ресурс Описание POST /api/v2/users/:user_id/ignore Ignore a user DELETE /api/v2/users/:user_id/ignore Unignore a user Abuse requests Ресурс Описание POST /api/v2/abuse_requests/offtopic Mark comment as offtopic POST /api/v2/abuse_requests/review Convert comment to review POST /api/v2/abuse_requests/abuse Create abuse about violation of site rules POST /api/v2/abuse_requests/spoiler Create abuse about spoiler in content Episode notifications Ресурс Описание POST /api/v2/episode_notifications Notify shikimori about anime episode release User rates Ресурс Описание GET /api/v2/user_rates List user rates GET /api/v2/user_rates/:id Show an user rate POST /api/v2/user_rates Create an user rate PATCH /api/v2/user_rates/:id Update an user rate PUT /api/v2/user_rates/:id Update an user rate POST /api/v2/user_rates/:id/increment Increment episodes/chapters by 1 DELETE /api/v2/user_rates/:id Destroy an user rate
