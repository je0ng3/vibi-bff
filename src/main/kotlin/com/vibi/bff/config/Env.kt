package com.vibi.bff.config

/**
 * `.env` 까지 포함해 환경변수를 읽는다. **[AppConfig] 밖에서 env 를 직접 읽는 코드는 이 헬퍼를 쓸 것** —
 * `loadDotenv` 가 `.env` 를 시스템 프로퍼티로 넣으므로 `System.getenv` 만 보면 로컬에서 조용히 무시된다.
 *
 * 우선순위는 loadDotenv 와 동일: 실제 env > 시스템 프로퍼티(= .env). blank 는 미설정.
 */
fun envOrProperty(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(key)?.takeIf { it.isNotBlank() }
