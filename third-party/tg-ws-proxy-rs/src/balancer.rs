use once_cell::sync::Lazy;
use parking_lot::RwLock;
use rand::seq::SliceRandom;
use std::collections::HashMap;
use std::time::{Duration, Instant};

/// Сколько базовый CF-домен считается «битым» после того, как Cloudflare
/// принял WS и сразу его закрыл.
const DEGRADE_TTL: Duration = Duration::from_secs(30);

// Порт proxy/balancer.py::_Balancer.
pub struct Balancer {
    domains: Vec<String>,
    dc_to_domain: HashMap<i32, String>,
    /// Базовые CF-домены, которые принимают WS и тут же рвут его.
    degraded: HashMap<String, Instant>,
}

pub static BALANCER: Lazy<RwLock<Balancer>> = Lazy::new(|| RwLock::new(Balancer::new()));

impl Balancer {
    pub fn new() -> Self {
        Self {
            domains: Vec::new(),
            dc_to_domain: HashMap::new(),
            degraded: HashMap::new(),
        }
    }

    pub fn update_domains_list(&mut self, domains_list: &[String]) {
        // Оригинал: if Counter(self.domains) == Counter(domains_list): return.
        // Отсортированное сравнение эквивалентно сравнению мультимножеств.
        let mut current_sorted = self.domains.clone();
        current_sorted.sort();
        let mut new_sorted = domains_list.to_vec();
        new_sorted.sort();

        if current_sorted == new_sorted {
            return;
        }

        if domains_list.is_empty() {
            return;
        }

        self.domains = domains_list.to_vec();
        let mut rng = rand::thread_rng();

        self.dc_to_domain.clear();
        for dc_id in [1, 2, 3, 4, 5, 203] {
            if let Some(domain) = self.domains.choose(&mut rng) {
                self.dc_to_domain.insert(dc_id, domain.clone());
            }
        }
    }

    pub fn update_domain_for_dc(&mut self, dc_id: i32, domain: &str) -> bool {
        if self.dc_to_domain.get(&dc_id).map(|s| s.as_str()) == Some(domain) {
            return false;
        }
        self.dc_to_domain.insert(dc_id, domain.to_string());
        true
    }

    /// Пометить базовый CF-домен «битым» на DEGRADE_TTL.
    ///
    /// Нужно для сессий, которые Cloudflare принимает и закрывает через доли
    /// секунды: раньше это считалось успехом, домен оставался закреплённым за
    /// DC, и каждый перезаход клиента (пачка из 10-20 соединений) снова бился
    /// в тот же край — снаружи это выглядело как минуты «Подключение к прокси».
    pub fn mark_domain_degraded(&mut self, domain: &str) {
        if domain.is_empty() {
            return;
        }
        let now = Instant::now();
        self.degraded.retain(|_, until| now < *until);
        self.degraded.insert(domain.to_string(), now + DEGRADE_TTL);
    }

    pub fn is_domain_degraded(&self, domain: &str) -> bool {
        match self.degraded.get(domain) {
            Some(until) => Instant::now() < *until,
            None => false,
        }
    }

    pub fn degraded_count(&self) -> usize {
        let now = Instant::now();
        self.degraded.values().filter(|until| now < **until).count()
    }

    pub fn get_domains_for_dc(&self, dc_id: i32) -> Vec<String> {
        let current_domain = self.dc_to_domain.get(&dc_id).cloned();
        let mut healthy: Vec<String> = Vec::new();
        let mut degraded: Vec<String> = Vec::new();

        // Закреплённый за DC домен идёт первым, но только если он не битый.
        if let Some(ref d) = current_domain {
            if self.is_domain_degraded(d) {
                degraded.push(d.clone());
            } else {
                healthy.push(d.clone());
            }
        }

        let mut shuffled = self.domains.clone();
        let mut rng = rand::thread_rng();
        shuffled.shuffle(&mut rng);

        for d in shuffled {
            if Some(&d) == current_domain.as_ref() {
                continue;
            }
            if self.is_domain_degraded(&d) {
                degraded.push(d);
            } else {
                healthy.push(d);
            }
        }

        // Битые — в хвост: их пробуем только когда живых не осталось.
        healthy.extend(degraded);
        healthy
    }
}
