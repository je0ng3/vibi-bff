package com.vibi.bff.config

/**
 * `.env` 까지 포함해 환경변수를 읽는다. **[AppConfig] 밖에서 env 를 직접 읽는 코드는 이 헬퍼를 쓸 것.**
 *
 * `Application.loadDotenv` 는 `.env` 항목을 프로세스 환경이 아니라 **시스템 프로퍼티**로 넣는다
 * (실제 env 를 덮어쓰지 않기 위해). 따라서 `System.getenv(...)` 만 보는 코드는 `.env` 에 설정한
 * 값을 조용히 무시하고 기본값으로 동작한다 — Cloud Run(실제 env)에서는 되는데 로컬에서만 안 되는,
 * 눈에 안 띄는 종류의 어긋남이다.
 *
 * 우선순위는 loadDotenv 와 동일: 실제 env > 시스템 프로퍼티(= .env). blank 는 미설정으로 본다.
 *
 * (기존 `System.getenv` 직접 호출 사이트들은 같은 gap 을 갖고 있으나 본 변경 범위 밖 — 손대는
 * 김에 하나씩 옮기면 된다.)
 */
fun envOrProperty(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(key)?.takeIf { it.isNotBlank() }
