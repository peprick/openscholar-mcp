package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openscholar.paper.InvalidPaperIdentifierException;
import com.openscholar.paper.PaperIdentifierType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaperIdentifierReferenceParserTests {

	@ParameterizedTest
	@MethodSource("acceptedIdentifiers")
	void parsesPasteableReferences(
			String input, PaperIdentifierType expectedType, String expectedNormalizedValue) {
		var parsed = PaperIdentifierReferenceParser.parse(input);

		assertThat(parsed.type()).isEqualTo(expectedType);
		assertThat(parsed.normalizedValue()).isEqualTo(expectedNormalizedValue);
	}

	@ParameterizedTest
	@MethodSource("rejectedIdentifiers")
	void rejectsUnsupportedAmbiguousOrUnsafeValues(String input) {
		assertThatThrownBy(() -> PaperIdentifierReferenceParser.parse(input))
				.isInstanceOf(InvalidPaperIdentifierException.class)
				.hasMessage("Identifier must be a DOI, arXiv identifier, or OpenAlex work identifier.");
	}

	private static java.util.stream.Stream<Arguments> acceptedIdentifiers() {
		return java.util.stream.Stream.of(
				Arguments.of("10.1000/ABC-123", PaperIdentifierType.DOI, "10.1000/abc-123"),
				Arguments.of("doi: 10.5555/Example.42", PaperIdentifierType.DOI, "10.5555/example.42"),
				Arguments.of(
						"10.978.86123/Legacy#Section",
						PaperIdentifierType.DOI,
						"10.978.86123/legacy#section"),
				Arguments.of(
						"10.1002/(SICI)1099-0844(199912)17:4<290::AID-CBF849>3.0.CO;2-P",
						PaperIdentifierType.DOI,
						"10.1002/(sici)1099-0844(199912)17:4<290::aid-cbf849>3.0.co;2-p"),
				Arguments.of(
						"https://DX.DOI.org/10.1038/s41586-020-2649-2",
						PaperIdentifierType.DOI,
						"10.1038/s41586-020-2649-2"),
				Arguments.of(
						"https://doi.org/10.978.86123/Legacy%23Section+One",
						PaperIdentifierType.DOI,
						"10.978.86123/legacy#section+one"),
				Arguments.of(
						"https://doi.org/10.1002/(SICI)1099-0844(199912)17:4%3C290::AID-CBF849%3E3.0.CO;2-P",
						PaperIdentifierType.DOI,
						"10.1002/(sici)1099-0844(199912)17:4<290::aid-cbf849>3.0.co;2-p"),
				Arguments.of(
						"https://doi.org/10.1002/(SICI)1099-0844(199912)17:4<290::AID-CBF849>3.0.CO;2-P",
						PaperIdentifierType.DOI,
						"10.1002/(sici)1099-0844(199912)17:4<290::aid-cbf849>3.0.co;2-p"),
				Arguments.of("2101.12345v3", PaperIdentifierType.ARXIV, "2101.12345"),
				Arguments.of("arXiv:hep-th/9901001v2", PaperIdentifierType.ARXIV, "hep-th/9901001"),
				Arguments.of(
						"https://export.arxiv.org/pdf/2401.01234v5.pdf",
						PaperIdentifierType.ARXIV,
						"2401.01234"),
				Arguments.of("W2741809807", PaperIdentifierType.OPENALEX, "w2741809807"),
				Arguments.of(
						"https://openalex.org/works/W2741809807",
						PaperIdentifierType.OPENALEX,
						"w2741809807"));
	}

	private static java.util.stream.Stream<String> rejectedIdentifiers() {
		return java.util.stream.Stream.of(
				null,
				"",
				"a paper title",
				"PMID:123456",
				"https://example.com/10.1000/example",
				"https://doi.org/10.1000/example?redirect=1",
				"https://doi.org/10.1000/example#fragment",
				"https://user@doi.org/10.1000/example",
				"https://doi.org:443/10.1000/example",
				"https://doi.org/10.1000/example%00hidden",
				"https://doi.org/10.1000/example%0Ahidden",
				"https://doi.org/10.1000/example%E2%80%8Bhidden",
				"https://doi.org/10.1000/example%FF",
				"10.1000/a b",
				"10.1234/x\uD800",
				"10.1234/x\uE000",
				"10.1234/" + "\u0130".repeat(504),
				"https://openalex.org/A2741809807",
				"2101.123",
				"2101.12345\nsecond-value",
				"W2741809807\u200B",
				"W" + "1".repeat(513));
	}
}
