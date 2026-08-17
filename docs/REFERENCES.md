# Official References

These links support version and architecture decisions made on 2026-08-16. Re-check them when application scaffolding or a dependency upgrade begins.

## Java and Spring

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring AI getting started and BOM](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Spring AI MCP server starters](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [Spring AI stateless MCP server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stateless-server-boot-starter-docs.html)
- [Spring AI MCP server annotations](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html)
- [Spring AI MCP security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html)
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
- [Unpaywall API](https://unpaywall.org/api)
- [arXiv API manual](https://info.arxiv.org/help/api/user-manual.html)
- [CORE API](https://core.ac.uk/services/api)
- [Directory of Open Access Journals](https://doaj.org/)
- [Open Access Theses and Dissertations](https://www.oatd.org/)
- [NDLTD thesis resources](https://ndltd.org/thesis-resources/find-etds/)

## Version caveat

The latest MCP specification may advance faster than the stable Java SDK. The repository therefore treats protocol support as a tested compatibility claim, not as a synonym for the newest published specification. See [ADR 0003](decisions/0003-supported-mcp-revision.md).
