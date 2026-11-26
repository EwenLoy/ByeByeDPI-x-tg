use crate::config::*;
use crate::ws::{is_http_status_error, RawWebSocket, WsError};
use crate::{ldebug, lerror, linfo, lwarn};
use serde::Deserialize;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

use once_cell::sync::Lazy;
static CFPROXY_SEM: Lazy<Semaphore> = Lazy::new(|| Semaphore::new(CFPROXY_GLOBAL_PARALLEL));

// ---------------------------------------------------------------------------
// CF connect: cooldown по таймаутам + TLS↔HTTP чередование.
// Порт proxy/cf_websocket.py (Python v1.12.0).
// ---------------------------------------------------------------------------

static CF_TIMEOUT_UNTIL: Lazy<parking_lot::RwLock<std::collections::HashMap<(String, bool), f64>>> =
    Lazy::new(|| parking_lot::RwLock::new(std::collections::HashMap::new()));

static CF_CONN_TLS: AtomicU64 = AtomicU64::new(0);
static CF_CONN_NO_TLS: AtomicU64 = AtomicU64::new(0);

pub fn reset_cf_timeouts() {
    CF_TIMEOUT_UNTIL.write().clear();
    CF_CONN_NO_TLS.store(0, Ordering::Relaxed);
    CF_CONN_TLS.store(0, Ordering::Relaxed);
}

pub fn cf_prefers_tls() -> bool {
    CF_CONN_TLS.load(Ordering::Relaxed) >= CF_CONN_NO_TLS.load(Ordering::Relaxed)
}

pub fn cf_tls_in_cooldown(name: &str) -> bool {
    let now = now_monotonic();
    let map = CF_TIMEOUT_UNTIL.read();
    map.get(&(name.to_string(), true))
        .map(|t| *t > now)
        .unwrap_or(false)
}

fn cf_timeout_get(key: &(String, bool)) -> Option<f64> {
    CF_TIMEOUT_UNTIL.read().get(key).copied()
}

fn cf_timeout_set(key: (String, bool), until: f64) {
    CF_TIMEOUT_UNTIL.write().insert(key, until);
}

fn cf_timeout_remove(key: &(String, bool)) {
    CF_TIMEOUT_UNTIL.write().remove(key);
}

/// Обновление списка cooldown перед установкой нового значения:
/// просроченные (until + CF_TIMEOUT_COOLDOWN <= now) вычищаются.
fn cf_timeout_prune_and_set(key: (String, bool), now: f64) {
    let mut map = CF_TIMEOUT_UNTIL.write();
    let stale: Vec<(String, bool)> = map
        .iter()
        .filter(|(_, until)| *until + CF_TIMEOUT_COOLDOWN <= now)
        .map(|(k, _)| k.clone())
        .collect();
    for k in stale {
        map.remove(&k);
    }
    map.insert(key, now + CF_TIMEOUT_COOLDOWN);
}

// ---------------------------------------------------------------------------
// Domain decoding
// ---------------------------------------------------------------------------

pub fn decode_cf_domain(s: &str) -> String {
    if !s.ends_with(".com") {
        return s.to_string();
    }
    let suffix = ".co.uk";
    let p = &s[..s.len() - 4];
    let mut n = 0i32;
    for c in p.chars() {
        if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') {
            n += 1;
        }
    }
    let mut result: Vec<u8> = Vec::new();
    for &c in p.as_bytes() {
        if c >= b'a' && c <= b'z' {
            let v = (((c - b'a') as i32 - n % 26 + 26) % 26) as u8 + b'a';
            result.push(v);
        } else if c >= b'A' && c <= b'Z' {
            let v = (((c - b'A') as i32 - n % 26 + 26) % 26) as u8 + b'A';
            result.push(v);
        } else {
            result.push(c);
        }
    }
    let mut out = String::from_utf8_lossy(&result).to_string();
    out.push_str(suffix);
    out
}

pub fn normalize_cf_domain(s: &str) -> String {
    let mut decoded = decode_cf_domain(s.trim()).trim().to_lowercase();
    while decoded.ends_with('.') {
        decoded.pop();
    }
    if decoded.is_empty() || !decoded.ends_with(".co.uk") {
        return String::new();
    }
    decoded
}

pub fn default_cfproxy_domains() -> Vec<String> {
    let mut domains = Vec::with_capacity(CFPROXY_ENC.len());
    for enc in CFPROXY_ENC {
        let d = normalize_cf_domain(enc);
        if !d.is_empty() {
            domains.push(d);
        }
    }
    domains
}

pub fn merge_cfproxy_domains(lists: &[Vec<String>]) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    let mut merged = Vec::new();
    for list in lists {
        for raw in list {
            let d = normalize_cf_domain(raw);
            if d.is_empty() || seen.contains(&d) {
                continue;
            }
            // Дополнительно валидируем как в _normalize_domain_pool оригинала.
            if !crate::config::is_valid_domain(&d) {
                continue;
            }
            seen.insert(d.clone());
            merged.push(d);
        }
    }
    merged
}

// ---------------------------------------------------------------------------
// 429 cooldown logic
// ---------------------------------------------------------------------------

pub fn clear_cfproxy_429_cooldowns() {
    CFPROXY_429.write().clear();
}

pub fn clear_cfproxy_429_cooldown(domain: &str) {
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return;
    }
    CFPROXY_429.write().remove(&d);
}

pub fn retry_after_delay(err: &WsError) -> Duration {
    let h = match err.handshake() {
        Some(h) => h,
        None => return Duration::ZERO,
    };
    let retry_after = h.headers.get("retry-after").map(|s| s.trim()).unwrap_or("");
    if retry_after.is_empty() {
        return Duration::ZERO;
    }
    if let Ok(seconds) = retry_after.parse::<i64>() {
        if seconds > 0 {
            return Duration::from_secs(seconds as u64);
        }
    }
    // http date parse (best-effort): пропускаем, как маловероятный кейс
    Duration::ZERO
}

pub fn next_cfproxy_429_cooldown_delay(prev: &Cfproxy429State, retry_after: Duration) -> Duration {
    if retry_after > Duration::ZERO {
        if retry_after > CFPROXY_429_MAX_COOLDOWN {
            return CFPROXY_429_MAX_COOLDOWN;
        }
        return retry_after;
    }
    let mut strikes = prev.strikes;
    let expired = match prev.until {
        None => true,
        Some(u) => u.elapsed() > CFPROXY_429_MAX_COOLDOWN,
    };
    if expired {
        strikes = 0;
    }
    let mut delay = CFPROXY_429_COOLDOWN;
    for _ in 0..strikes {
        delay *= 2;
        if delay >= CFPROXY_429_MAX_LADDER {
            return CFPROXY_429_MAX_LADDER;
        }
    }
    if delay > CFPROXY_429_MAX_LADDER {
        return CFPROXY_429_MAX_LADDER;
    }
    delay
}

pub fn mark_cfproxy_429_cooldown(domain: &str, err: &WsError) {
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return;
    }
    let retry_after = retry_after_delay(err);
    let mut map = CFPROXY_429.write();
    let prev = map.get(&d).cloned().unwrap_or_default();
    let delay = next_cfproxy_429_cooldown_delay(&prev, retry_after);
    let mut strikes = prev.strikes + 1;
    let expired = match prev.until {
        None => true,
        Some(u) => u.elapsed() > CFPROXY_429_MAX_COOLDOWN,
    };
    if expired {
        strikes = 1;
    }
    map.insert(
        d.clone(),
        Cfproxy429State {
            until: Some(Instant::now() + delay),
            strikes,
        },
    );
    drop(map);
    ldebug!(" CF cooldown {}: {:.0}s after 429", d, delay.as_secs_f64().ceil());
}

pub fn cfproxy_429_cooldown_remaining(domain: &str) -> Duration {
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return Duration::ZERO;
    }
    let map = CFPROXY_429.read();
    let state = match map.get(&d) {
        Some(s) => s.clone(),
        None => return Duration::ZERO,
    };
    drop(map);
    let until = match state.until {
        Some(u) => u,
        None => return Duration::ZERO,
    };
    let now = Instant::now();
    if until <= now {
        CFPROXY_429.write().remove(&d);
        return Duration::ZERO;
    }
    until - now
}

pub async fn acquire_cfproxy_attempt_slot() -> Option<tokio::sync::SemaphorePermit<'static>> {
    CFPROXY_SEM.acquire().await.ok()
}

// ---------------------------------------------------------------------------
// Cache files
// ---------------------------------------------------------------------------

fn cfproxy_cache_path() -> Option<PathBuf> {
    let dir = CFPROXY.read().cache_dir.trim().to_string();
    if dir.is_empty() {
        return None;
    }
    Some(PathBuf::from(dir).join(CFPROXY_CACHE_FILE_NAME))
}

// Активный домен больше не сохраняется в отдельный файл. Балансер работает в памяти.

fn load_cfproxy_domains_from_cache() -> Vec<String> {
    let path = match cfproxy_cache_path() {
        Some(p) => p,
        None => return Vec::new(),
    };
    let data = match std::fs::read_to_string(&path) {
        Ok(d) => d,
        Err(_) => return Vec::new(),
    };
    let list: Vec<String> = data.split('\n').map(|s| s.to_string()).collect();
    merge_cfproxy_domains(&[list])
}



fn save_cfproxy_domains_to_cache(domains: &[String]) {
    let path = match cfproxy_cache_path() {
        Some(p) => p,
        None => return,
    };
    if domains.is_empty() {
        return;
    }
    if let Some(parent) = path.parent() {
        if let Err(e) = std::fs::create_dir_all(parent) {
            ldebug!(" CF: кеш создать не удалось: {}", e);
            return;
        }
    }
    let data = domains.join("\n");
    if let Err(e) = std::fs::write(&path, data) {
        ldebug!(" CF: кеш сохранить не удалось: {}", e);
    }
}



fn should_refresh_cfproxy_domains() -> bool {
    let path = match cfproxy_cache_path() {
        Some(p) => p,
        None => return true,
    };
    let meta = match std::fs::metadata(&path) {
        Ok(m) => m,
        Err(_) => return true,
    };
    let modified = match meta.modified() {
        Ok(t) => t,
        Err(_) => return true,
    };
    match modified.elapsed() {
        Ok(elapsed) => elapsed >= CFPROXY_REFRESH_INTERVAL,
        Err(_) => true,
    }
}



pub fn init_cfproxy_domains() {
    let defaults = default_cfproxy_domains();
    let cached = load_cfproxy_domains_from_cache();

    let mut cfg = CFPROXY.write();
    if !cfg.user_domain.is_empty() {
        let ud = cfg.user_domain.clone();
        cfg.domains = vec![ud.clone()];
        crate::balancer::BALANCER.write().update_domains_list(&cfg.domains);
        return;
    }

    if !cached.is_empty() {
        let n = cached.len();
        cfg.domains = merge_cfproxy_domains(&[cached, defaults]);
        crate::balancer::BALANCER.write().update_domains_list(&cfg.domains);
        drop(cfg);
        linfo!(" CF: кеш доменов загружен ({} шт.)", n);
    } else {
        cfg.domains = defaults;
        crate::balancer::BALANCER.write().update_domains_list(&cfg.domains);
    }
}

pub fn start_cfproxy_refresh() {
    // Порт proxy/config.py::start_cfproxy_domain_refresh: сначала пробуем
    // обновить сразу (до 3 попыток), затем раз в час фоновым таском.
    // На мобиле уважаем свежесть дискового кеша для первой попытки.
    if !should_refresh_cfproxy_domains() {
        ldebug!(" CF: кеш свежий, откладываю обновление списка на час");
    }
    tokio::spawn(async move {
        if should_refresh_cfproxy_domains() {
            for _ in 0..3 {
                if try_refresh_cfproxy_domains().await {
                    break;
                }
                tokio::time::sleep(Duration::from_secs(10)).await;
            }
            if CFPROXY.read().domains.is_empty() {
                ldebug!(" CF: обновить список доменов не удалось, остаюсь на кеше/встроенном списке");
            }
        }
        loop {
            tokio::time::sleep(CFPROXY_REFRESH_INTERVAL).await;
            try_refresh_cfproxy_domains().await;
        }
    });
}

pub async fn try_refresh_cfproxy_domains() -> bool {
    let has_user = !CFPROXY.read().user_domain.is_empty();
    if has_user {
        return true;
    }

    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
    {
        Ok(c) => c,
        Err(_) => return false,
    };

    // Cache-buster как в оригинале ("?" + 7 случайных букв), UA tg-ws-proxy.
    let nonce: String = {
        use rand::Rng;
        let mut rng = rand::thread_rng();
        (0..7)
            .map(|_| rng.gen_range(b'a'..=b'z') as char)
            .collect()
    };
    let url = format!("{}?{}", CFPROXY_DOMAINS_URL, nonce);
    let resp = match client
        .get(&url)
        .header("User-Agent", "tg-ws-proxy")
        .send()
        .await
    {
        Ok(r) => r,
        Err(e) => {
            ldebug!(" CF: GitHub недоступен: {}", e);
            return false;
        }
    };
    if resp.status().as_u16() != 200 {
        ldebug!(" CF: GitHub вернул {}", resp.status().as_u16());
        return false;
    }
    let body = match resp.text().await {
        Ok(b) => b,
        Err(e) => {
            ldebug!(" CF: список доменов прочитать не удалось: {}", e);
            return false;
        }
    };

    let mut fetched_raw = Vec::new();
    for line in body.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        fetched_raw.push(line.to_string());
    }

    // Валидация пула как в оригинале: минимум CFPROXY_MIN_VALID_DOMAINS
    // валидных доменов, иначе игнорируем payload и остаёмся на текущем пуле.
    let decoded: Vec<String> = fetched_raw.iter().map(|d| normalize_cf_domain(d)).collect();
    let pool = normalize_domain_pool(&decoded);
    if pool.len() >= CFPROXY_MIN_VALID_DOMAINS {
        let merged = merge_cfproxy_domains(&[pool.clone(), default_cfproxy_domains()]);
        {
            let mut cfg = CFPROXY.write();
            if !cfg.user_domain.is_empty() {
                return true;
            }
            cfg.domains = merged.clone();
        }
        crate::balancer::BALANCER.write().update_domains_list(&merged);
        save_cfproxy_domains_to_cache(&merged);
        linfo!(" CF: список доменов обновлен ({} шт.)", pool.len());
        return true;
    }

    if !fetched_raw.is_empty() {
        lwarn!(
            " CF: игнорирую список доменов (total={}, valid={}, need>={}); остаюсь на текущем пуле",
            fetched_raw.len(),
            pool.len(),
            CFPROXY_MIN_VALID_DOMAINS
        );
    } else {
        ldebug!(" CF: обновить список доменов не удалось (пустой ответ), остаюсь на кеше/встроенном списке");
    }
    false
}

// ---------------------------------------------------------------------------
// DNS over HTTPS (DoH) resolve
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct DohAnswer {
    #[serde(rename = "data")]
    data: String,
    #[serde(rename = "type")]
    type_: i32,
}
#[derive(Deserialize)]
struct DohResponse {
    #[serde(rename = "Answer", default)]
    answer: Vec<DohAnswer>,
}

static DOH_CACHE: Lazy<parking_lot::RwLock<std::collections::HashMap<String, (String, Instant)>>> =
    Lazy::new(|| parking_lot::RwLock::new(std::collections::HashMap::new()));

fn pick_preferred_ip(candidates: &[String]) -> String {
    let mut fallback_v6 = String::new();
    for c in candidates {
        let c = c.trim();
        if let Ok(ip) = c.parse::<std::net::IpAddr>() {
            match ip {
                std::net::IpAddr::V4(v4) => return v4.to_string(),
                std::net::IpAddr::V6(v6) => {
                    if fallback_v6.is_empty() {
                        fallback_v6 = v6.to_string();
                    }
                }
            }
        }
    }
    fallback_v6
}

pub async fn resolve_doh(domain: &str) -> Option<String> {
    if let Some((ip, exp)) = DOH_CACHE.read().get(domain).cloned() {
        if Instant::now() < exp {
            return Some(ip);
        }
    }

    let endpoints = [
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/dns-query",
        "https://dns.quad9.net/dns-query",
        "https://dns.adguard-dns.com/dns-query",
    ];

    let client = reqwest::Client::builder()
        .timeout(Duration::from_millis(1500))
        .build()
        .ok()?;

    let (tx, mut rx) = tokio::sync::mpsc::channel(endpoints.len() + 1);
    let mut tasks = Vec::new();

    for u in endpoints {
        let client = client.clone();
        let domain = domain.to_string();
        let tx = tx.clone();
        tasks.push(tokio::spawn(async move {
            let full = format!("{}?name={}&type=A", u, domain);
            if let Ok(resp) = client
                .get(&full)
                .header("Accept", "application/dns-json")
                .send()
                .await
            {
                if resp.status().as_u16() == 200 {
                    if let Ok(r) = resp.json::<DohResponse>().await {
                        for ans in r.answer {
                            if ans.type_ == 1 {
                                let _ = tx.send(Some(ans.data)).await;
                                return;
                            }
                        }
                    }
                }
            }
            let _ = tx.send(None).await;
        }));
    }

    // UDP-резолв через системный resolver как дополнительный кандидат
    {
        let domain2 = domain.to_string();
        let tx = tx.clone();
        tasks.push(tokio::spawn(async move {
            let host = format!("{}:443", domain2);
            if let Ok(Ok(addrs)) = tokio::time::timeout(
                Duration::from_millis(1500),
                tokio::net::lookup_host(host),
            )
            .await
            {
                let ips: Vec<String> = addrs.map(|a| a.ip().to_string()).collect();
                let p = pick_preferred_ip(&ips);
                if !p.is_empty() {
                    let _ = tx.send(Some(p)).await;
                    return;
                }
            }
            let _ = tx.send(None).await;
        }));
    }

    drop(tx); // Чтобы rx.recv() завершился, когда все таски завершатся

    let deadline = tokio::time::sleep(Duration::from_millis(1500));
    tokio::pin!(deadline);

    let mut final_ip = None;
    loop {
        tokio::select! {
            _ = &mut deadline => {
                break;
            }
            msg = rx.recv() => {
                match msg {
                    Some(Some(ip)) => {
                        final_ip = Some(ip);
                        break;
                    }
                    Some(None) => {} // Таска ничего не нашла
                    None => break,   // Все таски завершились
                }
            }
        }
    }

    // Отменяем все незавершенные фоновые таски (исправление утечки)
    for t in tasks {
        t.abort();
    }

    if let Some(ip) = &final_ip {
        DOH_CACHE.write().insert(
            domain.to_string(),
            (ip.clone(), Instant::now() + Duration::from_secs(300)),
        );
    }
    
    final_ip
}

// ---------------------------------------------------------------------------
// cfConnectDomain
// ---------------------------------------------------------------------------

fn new_timed_attempt_timeout(base: Duration, phase: Duration) -> Duration {
    let mut eff = base;
    if eff <= Duration::ZERO {
        eff = Duration::from_secs(5);
    }
    if eff > phase {
        eff = phase;
    }
    eff
}

pub async fn cf_connect_domain(
    domain: &str,
    path: &str,
    timeout: f64,
    tls_name: Option<&str>,
    use_tls: bool,
) -> (Option<RawWebSocket>, String, Option<WsError>) {
    let path = if path.is_empty() { "/apiws" } else { path };

    let attempt_timeout = crate::ws::ws_connect_timeout(timeout);
    // Общий бюджет попытки = ladder (Python v1.12.0: min(timeout, ladder)).
    // Внутренние фазы (hostname / DoH+IP) капнуты модовым бюджетом фаз,
    // но суммарно могут занять весь ladder-бюджет.
    let total_budget = attempt_timeout;
    let phase_cap = total_budget.min(CFPROXY_DIAL_PHASE_TIMEOUT);

    let dial = async {
        let host_timeout = new_timed_attempt_timeout(phase_cap, phase_cap);
        let host_attempt = match (tls_name, use_tls) {
            (Some(name), _) => crate::ws::ws_connect_once_full(domain, domain, path, host_timeout, Some(name), use_tls).await,
            (None, true) => crate::ws::ws_connect_once_explicit(domain, domain, path, host_timeout, true).await,
            (None, false) => crate::ws::ws_connect_once_explicit(domain, domain, path, host_timeout, false).await,
        };
        match host_attempt {
            Ok(ws) => return (Some(ws), String::new(), None),
            Err(host_err) => {
                if is_http_status_error(&host_err, 429) {
                    return (None, String::new(), Some(host_err));
                }
                let resolved_ip = resolve_doh(domain).await.unwrap_or_default();
                if resolved_ip.is_empty() {
                    ldebug!(" CF DNS {} -> no result", domain);
                    return (None, String::new(), Some(host_err));
                }
                ldebug!(" CF DNS {} -> {}", domain, resolved_ip);
                let ip_timeout = new_timed_attempt_timeout(phase_cap, phase_cap);
                let ip_attempt = match (tls_name, use_tls) {
                    (Some(name), _) => crate::ws::ws_connect_once_full(&resolved_ip, domain, path, ip_timeout, Some(name), use_tls).await,
                    (None, true) => crate::ws::ws_connect_once_explicit(&resolved_ip, domain, path, ip_timeout, true).await,
                    (None, false) => crate::ws::ws_connect_once_explicit(&resolved_ip, domain, path, ip_timeout, false).await,
                };
                match ip_attempt {
                    Ok(ws) => (Some(ws), resolved_ip, None),
                    Err(e) => (None, resolved_ip, Some(e)),
                }
            }
        }
    };

    // Общий бюджет попытки ограничен ladder-таймаутом: превышение => Timeout
    // для cooldown-логики (как asyncio.wait_for в оригинале).
    match tokio::time::timeout(total_budget, dial).await {
        Ok(r) => r,
        Err(_) => (None, String::new(), Some(WsError::Timeout)),
    }
}

/// Порт cf_websocket.connect_cf_domains (Python v1.12.0).
/// candidates: пары (base, host=kwsN.base); возврат (ws, выбранный base).
/// Поведение:
///  * cooldown 30s на пару (base, режим) после TimeoutError;
///  * чередование TLS(443)/plain(80) при таймаутах: tls_first XOR (timeouts % 2);
///  * лестница таймаутов 3/3/5/5/10 c (в моде дополнительно capped бюджетом фаз);
///  * single-domain retry: дубль кандидата пробует противоположный режим;
///  * статистика успехов запоминает рабочий режим (cf_prefers_tls).
pub async fn connect_cf_domains(
    candidates: Vec<(String, String)>,
    label: &str,
    secure: bool,
    ws_path: &str,
    cancel: &CancellationToken,
) -> Option<(RawWebSocket, String)> {
    let mut domains = candidates;
    let retry_single_domain = secure && domains.len() == 1;
    if retry_single_domain {
        let dup = domains[0].clone();
        domains.push(dup);
    }

    let mut timeouts: usize = 0;
    let tls_first = cf_prefers_tls();

    for (index, (name, host)) in domains.into_iter().enumerate() {
        let mut use_tls = secure && (tls_first != (timeouts % 2 == 1));
        if retry_single_domain && index == 1 && timeouts == 0 {
            // Первый заход был пропущен из-за cooldown — пробуем другой режим.
            use_tls = !tls_first;
        }

        let key = (name.clone(), use_tls);
        let retry_at = cf_timeout_get(&key);
        if let Some(t) = retry_at {
            let now = now_monotonic();
            if now < t {
                continue;
            }
            // «Замок»: concurrent-клиенты не пойдут в тот же диал, пока мы сами дозваниваемся.
            cf_timeout_set(key.clone(), f64::INFINITY);
        }

        let ladder = CF_CONNECT_TIMEOUTS[timeouts.min(CF_CONNECT_TIMEOUTS.len() - 1)];
        let attempt_timeout = ladder.min(10.0);

        ldebug!(" CF try {}", host);
        let _permit = match acquire_cfproxy_attempt_slot().await {
            Some(p) => p,
            None => {
                if let Some(orig) = retry_at {
                    cf_timeout_set(key.clone(), orig);
                }
                return None;
            }
        };
        // SNI = базовый домен (DPI режет ClientHello с kwsN.*), Host = kwsN.<base>.
        let tls_name: Option<String> = if use_tls { Some(name.clone()) } else { None };
        let dial = cf_connect_domain(&host, ws_path, attempt_timeout, tls_name.as_deref(), use_tls);
        tokio::pin!(dial);
        let outcome = tokio::select! {
            _ = cancel.cancelled() => None,
            r = &mut dial => Some(r),
        };

        match outcome {
            // Клиент умер / стоп-proxy: вернуть ранее существовавший замок.
            None => {
                if let Some(orig) = retry_at {
                    if cf_timeout_get(&key) == Some(f64::INFINITY) {
                        cf_timeout_set(key.clone(), orig);
                    }
                }
                return None;
            }
            Some((ws_opt, ip, err)) => {
                match (ws_opt, err) {
                    (Some(ws), _) => {
                        if use_tls {
                            CF_CONN_TLS.fetch_add(1, Ordering::Relaxed);
                        } else {
                            CF_CONN_NO_TLS.fetch_add(1, Ordering::Relaxed);
                        }
                        if !ip.is_empty() {
                            ldebug!(" CF ok {} via {}", host, ip);
                        } else {
                            ldebug!(" CF ok {} via hostname", host);
                        }
                        cf_timeout_remove(&key);
                        clear_cfproxy_429_cooldown(&name);
                        return Some((ws, name));
                    }
                    (None, Some(WsError::Timeout)) => {
                        let now = now_monotonic();
                        // Python: cooldown ставим, если у нас был свой retry_at
                        // ИЛИ ключа в карте ещё нет (не затираем чужой свежий).
                        if retry_at.is_some() || cf_timeout_get(&key).is_none() {
                            cf_timeout_prune_and_set(key.clone(), now);
                            lwarn!(
                                "[{}] CF WebSocket timed out (host={}; tls={}); retry in {:.0}s",
                                label,
                                host,
                                use_tls,
                                CF_TIMEOUT_COOLDOWN
                            );
                        }
                        timeouts += 1;
                        if timeouts >= CF_CONNECT_TIMEOUTS.len() {
                            break;
                        }
                        // Python: timeout -> continue, tail-break single-domain
                        // не выполняется (dup-кандидат обязан сменить режим).
                        continue;
                    }
                    (None, Some(e)) => {
                        // Python 1.12: HTTP-ошибки лестницу НЕ съедают.
                        // 429 в моде ставит свой ladder-cooldown (cfproxy_429).
                        if is_http_status_error(&e, 429) {
                            mark_cfproxy_429_cooldown(&name, &e);
                        }
                        if !ip.is_empty() {
                            log_cf_conn_error(
                                &format!(" CF fail {} via {}: {}", host, ip, e.compact()),
                                &e,
                            );
                        } else {
                            log_cf_conn_error(&format!(" CF fail {}: {}", host, e.compact()), &e);
                        }
                        if retry_at.is_some() && cf_timeout_get(&key) == Some(f64::INFINITY) {
                            cf_timeout_set(key.clone(), retry_at.unwrap());
                        }
                        cf_timeout_remove(&key);
                    }
                    (None, None) => {
                        if retry_at.is_some() && cf_timeout_get(&key) == Some(f64::INFINITY) {
                            cf_timeout_set(key.clone(), retry_at.unwrap());
                        }
                        cf_timeout_remove(&key);
                    }
                }
            }
        }

        if retry_single_domain {
            // Python: у одиночного кандидата после попытки перебор закончен.
            break;
        }
    }

    None
}

pub fn log_cf_conn_error(msg: &str, err: &WsError) {
    if let WsError::Io(e) = err {
        if e.kind() == std::io::ErrorKind::ConnectionReset {
            return;
        }
    }
    if is_http_status_error(err, 429) {
        lwarn!("{}", msg);
    } else {
        lerror!("{}", msg);
    }
}

// активный домен set/save
pub fn set_active_domain_and_save(_chosen: &str) {
    // Больше не используется для файлов. Балансер обновляется внутри proxy.rs
}