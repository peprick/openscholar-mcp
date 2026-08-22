# Official References

These links support version and architecture decisions reviewed through 2026-08-22. Re-check them when application scaffolding, model selection, or a dependency upgrade begins.

## Java and Spring

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring AI getting started and BOM](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Spring AI MCP server starters](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [Spring AI stateless MCP server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stateless-server-boot-starter-docs.html)
- [Spring AI MCP server annotations](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html)
- [Spring AI MCP security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html)
- [Spring AI Embedding Model API](https://docs.spring.io/spring-ai/reference/api/embeddings.html)
- [Spring AI Ollama embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/ollama-embeddings.html)
- [Spring AI OpenAI embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/openai-embeddings.html)
- [Spring AI pgvector integration](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)
- [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Boot Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)

## MCP

- [Official MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
- [MCP Java SDK 2.0 release](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0)
- [MCP specification](https://modelcontextprotocol.io/specification/)
- [MCP transports](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports)
- [MCP authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)
- [MCP security best practices](https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices)
- [MCP conformance suite](https://github.com/modelcontextprotocol/conformance)
- [MCP Inspector](https://github.com/modelcontextprotocol/inspector)

## Research providers

- [OpenAlex API overview](https://help.openalex.org/api/)
- [OpenAlex authentication and rate limits](https://help.openalex.org/api/authentication/)
- [OpenAlex search](https://help.openalex.org/api/searching/)
- [OpenAlex error handling](https://help.openalex.org/api/errors/)
- [Unpaywall REST API, exact DOI endpoint, email, and limits](https://unpaywall.org/api)
- [arXiv API manual, `id_list`, Atom responses, and request pacing](https://info.arxiv.org/help/api/user-manual.html)
- [arXiv API terms of use and content restrictions](https://info.arxiv.org/help/api/tou.html)
- [CORE API v3 documentation](https://api.core.ac.uk/docs/v3)
- [CORE API v3 OpenAPI contract](https://api.core.ac.uk/swagger/v3.json)
- [CORE API service, registration, and current quotas](https://core.ac.uk/services/api)
- [CORE terms and licence eligibility](https://core.ac.uk/terms)
- [CORE attribution guidance](https://core.ac.uk/acknowledge)
- [DOAJ current API documentation](https://doaj.org/api)
- [DOAJ v4 current-API cutover notice](https://blog.doaj.org/2024/07/16/were-cutting-over-to-the-new-api/)
- [DOAJ v4 article-search route source](https://github.com/DOAJ/doaj/blob/develop/portality/view/api_v4.py)
- [DOAJ current discovery implementation and public paging bounds](https://github.com/DOAJ/doaj/blob/develop/portality/api/current/discovery.py)
- [DOAJ current article discovery schema](https://github.com/DOAJ/doaj/blob/develop/portality/api/current/discovery_api_article_swag.json)
- [DOAJ FAQ, metadata CC0 terms, and underlying-work copyright](https://doaj.org/docs/faq/)
- [DOAJ OAI-PMH article metadata notes](https://doaj.org/docs/oai-pmh/)
- [Open Access Theses and Dissertations](https://www.oatd.org/)
- [NDLTD thesis resources](https://ndltd.org/thesis-resources/find-etds/)

## Embeddings and vector search

- [Qwen3-Embedding-0.6B official model card, dimensions, context, licence, and evaluation](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B)
- [Qwen3 Embedding technical report, 2025-06-05](https://arxiv.org/abs/2506.05176)
- [Official Qwen3 Embedding repository](https://github.com/QwenLM/Qwen3-Embedding)
- [Ollama Qwen3 embedding model tags and artifact sizes](https://ollama.com/library/qwen3-embedding/tags)
- [Ollama embeddings API and normalized output](https://docs.ollama.com/capabilities/embeddings)
- [Ollama local-only/cloud controls](https://docs.ollama.com/faq)
- [Ollama privacy policy](https://ollama.com/privacy)
- [OpenAI `text-embedding-3-large` model and current pricing](https://developers.openai.com/api/docs/models/text-embedding-3-large)
- [OpenAI embedding dimensions and published benchmark context](https://openai.com/index/new-embedding-models-and-api-updates/)
- [OpenAI API data controls and retention](https://platform.openai.com/docs/models/default-usage-policies-by-endpoint)
- [OpenAI enterprise privacy, updated 2026-01-08](https://openai.com/enterprise-privacy/)
- [pgvector exact search, HNSW, dimensions, and storage](https://github.com/pgvector/pgvector)

## Outbound-link security

- [Apache HttpClient 5.6](https://hc.apache.org/httpcomponents-client-5.6.x/)
- [Apache HttpClient `DnsResolver`](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/DnsResolver.html)

## Citation formats and identifiers

- [CSL-JSON 1.0.2 input schema](https://raw.githubusercontent.com/citation-style-language/schema/v1.0.2/schemas/input/csl-data.json)
- [Citation Style Language 1.0.2 specification](https://docs.citationstyles.org/en/v1.0.2/specification.html)
- [Oren Patashnik, *BibTeXing*](https://tug.ctan.org/biblio/bibtex/contrib/doc/btxdoc.pdf)
- [DataCite citation content negotiation](https://support.datacite.org/docs/datacite-content-resolver)
- [DOI Handbook](https://www.doi.org/doi-handbook/html/)
- [arXiv identifier rules](https://info.arxiv.org/help/arxiv_identifier.html)
- [arXiv citation guidance](https://info.arxiv.org/help/faq/references.html)

## Browser PDF rendering

- [PDF.js 6.2.108 release](https://github.com/mozilla/pdf.js/releases/tag/v6.2.108)
- [PDF.js getting started](https://mozilla.github.io/pdf.js/getting_started/)
- [PDF.js examples](https://mozilla.github.io/pdf.js/examples/)
- [PDF.js cross-origin FAQ](https://github.com/mozilla/pdf.js/wiki/frequently-asked-questions)

## Deployment and operations

- [Docker Compose production guidance](https://docs.docker.com/compose/how-tos/production/)
- [Docker Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)
- [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https)
- [Caddy global server options](https://caddyserver.com/docs/caddyfile/options)
- [Caddy request-body limits](https://caddyserver.com/docs/caddyfile/directives/request_body)
- [Caddy access-log filtering](https://caddyserver.com/docs/caddyfile/directives/log#filter)
- [Prometheus configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)
- [Prometheus alerting rules](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)
- [PostgreSQL `pg_dump`](https://www.postgresql.org/docs/17/app-pgdump.html)
- [PostgreSQL `pg_restore`](https://www.postgresql.org/docs/17/app-pgrestore.html)
- [GitHub dependency review](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/about-dependency-review)
- [GitHub CodeQL code scanning](https://docs.github.com/en/code-security/code-scanning/introduction-to-code-scanning/about-code-scanning-with-codeql)
- [CycloneDX specification](https://cyclonedx.org/specification/overview/)

## Version caveat

The latest MCP specification may advance faster than the stable Java SDK. The repository therefore treats protocol support as a tested compatibility claim, not as a synonym for the newest published specification. See [ADR 0003](decisions/0003-supported-mcp-revision.md).
