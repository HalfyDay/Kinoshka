// Kinoshka: пример JS-плагина источника (вариант C) — все три раздела.
//
// Установка: Источники → Свои → Добавить → вид «JS-плагин» →
// вставить URL этого файла → «Проверить» → «Сохранить» (дважды:
// первый раз показывает предупреждение песочницы, второй сохраняет).
//
// Контракт (подробно — в README.md рядом):
//   manifest()      → JSON-строка {name, version, author?, description?, kinoshkaApi: 1}
//   resolveMovie(s) → JSON-строка {voices[] и/или tracks[]},
//                     где s — JSON-строка {kinopoiskId: 301|null, imdbId: "tt…"|null}
// В песочнице доступны только: log(), httpGet(), httpPost().
// Никаких java.*/Packages — их нет (см. «Песочница» в README).
//
// Как один resolveMovie покрывает все разделы:
//   ФИЛЬМ   → voices[] (одна строка пикера на элемент);
//   СЕРИАЛ  → tracks[] (серия на элемент, playerUrl играет по тапу);
//   АНИМЕ   → те же tracks[] (группируются по дабу в строки озвучек,
//             эпизоды нумеруются по episode; voices без tracks дадут
//             строки с одной серией — для полнометражек).
// Плагин не знает, из какого раздела его вызвали, — отдавайте обе формы
// сразу, пикер каждой секции заберёт свою (пустые массивы = «нет такого»).
//
// Этот пример — шаблон под embed-хост вида:
//   фильм:  https://HOST/embed/kp/{kp}        → file:"https://cdn…/….m3u8" в HTML
//   сериал: https://HOST/embed/serial/kp/{kp} → <div class="ep" …> на серию
// Подставьте свой BASE и свой парсинг — остальное уже работает: пикер,
// плеер, скачивание, кнопка «Обновить» (по manifest.version).

var BASE = "https://video.example.com";

function manifest() {
    return JSON.stringify({
        name: "Example Embed",
        version: "1.1.0",
        author: "@kinoshka",
        description: "Шаблонный плагин: фильм (voices) + сериал/аниме (tracks)",
        kinoshkaApi: 1
    });
}

function resolveMovie(arg) {
    var ctx = JSON.parse(arg);

    // Без Kinopoisk ID этот шаблонный хост искать не умеет — честно пусто.
    // (Если ваш хост умеет поиск по IMDb — ветвите здесь по ctx.imdbId.)
    if (!ctx.kinopoiskId) {
        log("example: no kinopoiskId, skip");
        return JSON.stringify({ voices: [], tracks: [] });
    }

    return JSON.stringify({
        voices: resolveFilm(ctx.kinopoiskId),
        tracks: resolveSeries(ctx.kinopoiskId)
    });
}

// --- ФИЛЬМ: одна embed-страница → один поток → одна строка пикера ---
function resolveFilm(kp) {
    var page = httpGet(BASE + "/embed/kp/" + kp, { Referer: BASE + "/" });
    if (page.status !== 200) {
        log("example film: embed http " + page.status + " " + page.error);
        return [];
    }

    // Типичный след плеера в embed-HTML: file:"https://…/master.m3u8"
    var m = /file\s*:\s*"([^"]+\.(m3u8|mp4)[^"]*)"/.exec(page.body);
    if (!m) {
        log("example film: no stream in embed page (film-only title?)");
        return [];
    }

    return [{ title: "Дубляж", url: m[1], headers: { Referer: BASE + "/" } }];
}

// --- СЕРИАЛ и АНИМЕ: страница со списком серий → tracks[] ---
//
// Хост отдаёт серии разметкой вида:
//   <div class="ep" data-n="1" data-title="Пилот"
//        data-file="https://cdn…/s1e1.m3u8"
//        data-1080="https://cdn…/s1e1_1080.m3u8"
//        data-720="https://cdn…/s1e1_720.m3u8"></div>
// data-1080/data-720 необязательны: без них серия играет по data-file (Auto).
// dub/dubTitle: один даб на странице — константы; если хост отдаёт несколько
// дабов — парсите их в цикл и кладите свой dub- slug на даб (аниме-пикер
// сгруппирует треки по дабам в отдельные строки).
function resolveSeries(kp) {
    var page = httpGet(BASE + "/embed/serial/kp/" + kp, { Referer: BASE + "/" });
    if (page.status !== 200) {
        log("example series: embed http " + page.status + " " + page.error);
        return [];
    }

    var re = /<div class="ep" data-n="(\d+)" data-title="([^"]*)" data-file="([^"]+)"(?: data-1080="([^"]+)")?(?: data-720="([^"]+)")?/g;
    var tracks = [];
    var m;
    while ((m = re.exec(page.body)) !== null) {
        var track = {
            season: 1,
            episode: parseInt(m[1], 10),
            title: m[2],
            url: m[3],
            dub: "studio",
            dubTitle: "Студия Пример",
            headers: { Referer: BASE + "/" }
        };
        // Лестница качества: играет лучшее (первое не-Auto). Ключи — как
        // подписи в плеере; битые URL приложение отфильтрует само.
        var qualities = {};
        if (m[4]) qualities["1080p"] = m[4];
        if (m[5]) qualities["720p"] = m[5];
        if (Object.keys(qualities).length > 0) track.qualities = qualities;
        tracks.push(track);
    }
    if (tracks.length === 0) log("example series: no episodes (movie-only title?)");
    return tracks;
}
