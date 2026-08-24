package com.openscholar.mcp.internal;

enum McpToolErrorCode {

	INVALID_REQUEST(Category.INVALID_INPUT,
			"The tool arguments are invalid. Review the tool schema, correct them, and try again.",
			Action.CORRECT_INPUT),

	INVALID_PAPER_IDENTIFIER(Category.INVALID_INPUT,
			"Use a DOI, arXiv identifier, or OpenAlex work identifier.",
			Action.CORRECT_INPUT),

	UNSUPPORTED_CITATION_FORMAT(Category.INVALID_INPUT, "Use bibtex or csl-json.", Action.CORRECT_INPUT),

	PAPER_NOT_FOUND(Category.NOT_FOUND, "No stored paper matches that OpenScholar paper ID.",
			Action.USE_DIFFERENT_PAPER),

	PAPER_IDENTIFIER_NOT_FOUND(Category.NOT_FOUND,
			"No paper in this OpenScholar workspace matches that identifier. Search by topic first.",
			Action.SEARCH_FIRST),

	COLLECTION_NOT_FOUND(Category.NOT_FOUND,
			"No collection in this OpenScholar library matches that collection ID.",
			Action.SELECT_VISIBLE_COLLECTION),

	SEARCH_COORDINATION_TIMEOUT(Category.TRANSIENT, "Search is busy. Retry the same request.", Action.RETRY),

	SEARCH_COORDINATION_INTERRUPTED(Category.TRANSIENT,
			"Search was interrupted before it completed. Retry the same request.", Action.RETRY),

	SEARCH_DEADLINE_EXCEEDED(Category.TRANSIENT,
			"Search exceeded its execution time limit. Retry later or narrow the request.", Action.RETRY),

	SEARCH_EXECUTION_INTERRUPTED(Category.TRANSIENT,
			"Search was interrupted before it completed. Retry the same request.", Action.RETRY),

	SEARCH_PROVIDER_UNAVAILABLE(Category.UPSTREAM_UNAVAILABLE,
			"Research sources could not complete the search.",
			Action.RETRY_OR_USE_LOCAL_SEARCH, Action.USE_LOCAL_SEARCH),

	ACCESS_REFRESH_RATE_LIMITED(Category.RATE_LIMITED,
			"Full-text access was checked too recently. Wait before refreshing again.", Action.WAIT_AND_RETRY),

	ACCESS_PROVIDERS_UNAVAILABLE(Category.UPSTREAM_UNAVAILABLE,
			"Full-text sources could not complete the request.", Action.RETRY, Action.CONTACT_OPERATOR),

	MCP_RESPONSE_TOO_LARGE(Category.RESOURCE_LIMIT,
			"The tool result exceeds the response budget. Request fewer results or a narrower page.",
			Action.REDUCE_RESULT_SIZE),

	MCP_TOOL_FAILED(Category.INTERNAL, "The tool could not complete safely.", Action.CONTACT_OPERATOR);

	private final Category category;

	private final String message;

	private final Action action;

	private final Action nonRetryableAction;

	McpToolErrorCode(Category category, String message, Action action) {
		this(category, message, action, action);
	}

	McpToolErrorCode(Category category, String message, Action action, Action nonRetryableAction) {
		this.category = category;
		this.message = message;
		this.action = action;
		this.nonRetryableAction = nonRetryableAction;
	}

	String category() {
		return category.name();
	}

	String message() {
		return message;
	}

	String action(boolean retryable) {
		return (retryable ? action : nonRetryableAction).name();
	}

	private enum Category {
		INVALID_INPUT,
		NOT_FOUND,
		TRANSIENT,
		UPSTREAM_UNAVAILABLE,
		RATE_LIMITED,
		RESOURCE_LIMIT,
		INTERNAL
	}

	private enum Action {
		CORRECT_INPUT,
		USE_DIFFERENT_PAPER,
		SEARCH_FIRST,
		SELECT_VISIBLE_COLLECTION,
		RETRY,
		RETRY_OR_USE_LOCAL_SEARCH,
		USE_LOCAL_SEARCH,
		WAIT_AND_RETRY,
		REDUCE_RESULT_SIZE,
		CONTACT_OPERATOR
	}
}
