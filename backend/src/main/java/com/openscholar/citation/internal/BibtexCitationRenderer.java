package com.openscholar.citation.internal;

import java.util.ArrayList;
import java.util.List;

final class BibtexCitationRenderer {

	String render(CitationItem item) {
		List<Field> fields = new ArrayList<>();
		if (!item.authors().isEmpty()) {
			fields.add(new Field("author", item.authors().stream()
					.map(author -> "{" + escape(author) + "}")
					.collect(java.util.stream.Collectors.joining(" and "))));
		}
		fields.add(new Field("title", "{" + escape(item.title()) + "}"));
		addVenue(fields, item);
		add(fields, "publisher", item.publisher());
		addInstitution(fields, item);
		add(fields, "volume", item.volume());
		add(fields, "number", item.issue());
		add(fields, "pages", item.pages());
		add(fields, "eid", item.articleNumber());
		add(fields, "edition", item.edition());
		add(fields, "isbn", joined(item.isbn()));
		add(fields, "issn", joined(item.issn()));
		if (item.effectiveYear() > 0) {
			fields.add(new Field("year", Integer.toString(item.effectiveYear())));
		}
		if (item.publicationDate() != null) {
			fields.add(Field.bare("month", month(item.publicationDate().getMonthValue())));
		}
		addThesisType(fields, item);
		if (item.documentType() == com.openscholar.paper.DocumentType.PREPRINT) {
			String note = item.arxivId() == null
					? "Preprint"
					: "Preprint, arXiv:" + escape(item.arxivId());
			fields.add(new Field("note", note));
		}
		if (item.doi() != null) {
			fields.add(new Field("doi", escape(item.doi())));
		}
		if (item.arxivId() != null) {
			fields.add(new Field("eprint", escape(item.arxivId())));
			fields.add(new Field("archivePrefix", "arXiv"));
		}
		if (item.pmid() != null) {
			fields.add(new Field("pmid", item.pmid()));
		}
		if (item.pmcid() != null) {
			fields.add(new Field("pmcid", item.pmcid()));
		}
		if (item.canonicalUrl() != null) {
			fields.add(new Field("url", escape(item.canonicalUrl())));
		}
		if (item.language() != null) {
			fields.add(new Field("language", escape(item.language())));
		}

		StringBuilder output = new StringBuilder();
		output.append('@').append(entryType(item)).append('{').append(item.citationKey()).append(",\n");
		for (Field field : fields) {
			output.append("  ")
					.append(field.name())
					.append(" = ");
			if (field.bare()) {
				output.append(field.value());
			}
			else {
				output.append('{').append(field.value()).append('}');
			}
			output.append(",\n");
		}
		return output.append("}\n").toString();
	}

	private static void add(List<Field> fields, String name, String value) {
		if (value != null) {
			fields.add(new Field(name, escape(value)));
		}
	}

	private static void addInstitution(List<Field> fields, CitationItem item) {
		if (item.institution() == null) {
			return;
		}
		String field = switch (item.documentType()) {
			case THESIS, DISSERTATION -> "school";
			case ARTICLE, PREPRINT, CONFERENCE_PAPER, BOOK, BOOK_CHAPTER, REPORT, DATASET, OTHER ->
					"institution";
		};
		add(fields, field, item.institution());
	}

	private static void addThesisType(List<Field> fields, CitationItem item) {
		String type = switch (item.documentType()) {
			case THESIS -> item.degree() == null ? "Thesis" : item.degree();
			case DISSERTATION -> item.degree() == null ? "Dissertation" : item.degree();
			case ARTICLE, PREPRINT, CONFERENCE_PAPER, BOOK, BOOK_CHAPTER, REPORT, DATASET, OTHER -> null;
		};
		add(fields, "type", type);
	}

	private static String joined(List<String> values) {
		if (values.isEmpty()) {
			return null;
		}
		return values.stream()
				.distinct()
				.sorted()
				.collect(java.util.stream.Collectors.joining(", "));
	}

	private static String entryType(CitationItem item) {
		return switch (item.documentType()) {
			case ARTICLE -> "article";
			case PREPRINT -> "unpublished";
			case CONFERENCE_PAPER -> "inproceedings";
			case DISSERTATION -> "phdthesis";
			case BOOK -> "book";
			case BOOK_CHAPTER -> "incollection";
			case REPORT -> "techreport";
			case THESIS, DATASET, OTHER -> "misc";
		};
	}

	private static void addVenue(List<Field> fields, CitationItem item) {
		if (item.venueName() == null) {
			return;
		}
		String field = switch (item.documentType()) {
			case ARTICLE -> "journal";
			case CONFERENCE_PAPER, BOOK_CHAPTER -> "booktitle";
			case PREPRINT, THESIS, DISSERTATION, BOOK, REPORT, DATASET, OTHER -> null;
		};
		if (field != null) {
			fields.add(new Field(field, escape(item.venueName())));
		}
	}

	private static String month(int month) {
		return switch (month) {
			case 1 -> "jan";
			case 2 -> "feb";
			case 3 -> "mar";
			case 4 -> "apr";
			case 5 -> "may";
			case 6 -> "jun";
			case 7 -> "jul";
			case 8 -> "aug";
			case 9 -> "sep";
			case 10 -> "oct";
			case 11 -> "nov";
			case 12 -> "dec";
			default -> throw new IllegalArgumentException("Month must be between 1 and 12");
		};
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			escaped.append(switch (character) {
				case '\\' -> "\\textbackslash{}";
				case '{' -> "\\{";
				case '}' -> "\\}";
				case '#' -> "\\#";
				case '$' -> "\\$";
				case '%' -> "\\%";
				case '&' -> "\\&";
				case '_' -> "\\_";
				case '^' -> "\\textasciicircum{}";
				case '~' -> "\\textasciitilde{}";
				default -> Character.toString(character);
			});
		}
		return escaped.toString();
	}

	private record Field(String name, String value, boolean bare) {

		private Field(String name, String value) {
			this(name, value, false);
		}

		private static Field bare(String name, String value) {
			return new Field(name, value, true);
		}
	}
}
