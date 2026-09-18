// Kinoshka: пример JS-плагина источника (вариант C).
//
// Установка: Источники → Свои → Добавить → вид «JS-плагин» →
// вставить URL этого файла → «Проверить» → «Сохранить» (дважды:
// первый раз показывает предупреждение песочницы, второй сохраняет).
//
// Контракт (подробно — в README.md рядом):
//   manifest()      → JSON-строка {name, version, author?, description?, kinoshkaApi: 1}
//   resolveMovie(s) → JSON-строка {voices[]} и/или {tracks[]},
//                     где s — JSON-строка {kinopoiskId: 301|null, imdbId: "tt…"|null}
// В песочнице доступны только: log(), httpGet(), httpPost().
// Никаких java.*/Packages — их нет (см. «Песочница» в README).
//
// Этот пример — шаблон под embed-хост вида https://HOST/embed/kp/{kp},
// отдающий в HTML ссылку file:"https://cdn…/….m3u8". Подставьте свой BASE
// и свой способ вытащить ссылку — остальное уже работает: пикер, плеер,
// скачивание, кнопка «Обновить» (по manifest.version).

var BASE = "https://video.example.com";

function manifest() {
    return JSON.stringify({
        name: "Example Embed",
        version: "1.0.0",
        author: "@kinoshka",
        description: "Шаблонный плагин: поток из embed-страницы по Kinopoisk ID",
        kinoshkaApi: 1
    });
}

function resolveMovie(arg) {
    var ctx = JSON.parse(arg);

    // Без Kinopoisk ID этот шаблонный хост искать не умеет — честно пусто.
    // (Если ваш хост умеет поиск по IMDb — ветвите здесь по ctx.imdbId.)
    if (!ctx.kinopoiskId) {
        log("example: no kinopoiskId, skip");
        return JSON.stringify({ voices: [] });
    }

    var page = httpGet(BASE + "/embed/kp/" + ctx.kinopoiskId, { Referer: BASE + "/" });
    if (page.status !== 200) {
        log("example: embed http " + page.status + " " + page.error);
        return JSON.stringify({ voices: [] });
    }

    // Типичный след плеера в embed-HTML: file:"https://…/master.m3u8"
    var m = /file\s*:\s*"([^"]+\.(m3u8|mp4)[^"]*)"/.exec(page.body);
    if (!m) {
        log("example: no stream in embed page");
        return JSON.stringify({ voices: [] });
    }

    return JSON.stringify({
        voices: [
            { title: "Дубляж", url: m[1], headers: { Referer: BASE + "/" } }
        ]
    });
}
