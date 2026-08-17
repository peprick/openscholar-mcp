package com.openscholar.access.internal.provider.arxiv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

final class ArxivFeedParser {

	private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
	private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

	ArxivFeed parse(byte[] body) {
		if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
			throw new ArxivFeedParseException("arXiv returned an empty or oversized feed", null);
		}
		try {
			DocumentBuilderFactory factory = secureFactory();
			Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
			Element root = document.getDocumentElement();
			if (root == null
					|| !"feed".equals(root.getLocalName())
					|| !ATOM_NAMESPACE.equals(root.getNamespaceURI())) {
				throw new ArxivFeedParseException("arXiv returned a non-Atom document", null);
			}

			List<ArxivFeedEntry> entries = new ArrayList<>();
			NodeList nodes = root.getElementsByTagNameNS(ATOM_NAMESPACE, "entry");
			for (int index = 0; index < nodes.getLength(); index++) {
				Element entry = (Element) nodes.item(index);
				entries.add(new ArxivFeedEntry(
						childText(entry, ATOM_NAMESPACE, "id"),
						childText(entry, ATOM_NAMESPACE, "title"),
						childText(entry, ATOM_NAMESPACE, "updated"),
						childText(entry, ATOM_NAMESPACE, "published"),
						license(entry),
						links(entry)));
			}
			return new ArxivFeed(List.copyOf(entries));
		}
		catch (ParserConfigurationException | SAXException | IOException exception) {
			throw new ArxivFeedParseException("arXiv returned malformed XML", exception);
		}
	}

	private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setXIncludeAware(false);
		factory.setExpandEntityReferences(false);
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		return factory;
	}

	private static String childText(Element parent, String namespace, String localName) {
		NodeList children = parent.getChildNodes();
		for (int index = 0; index < children.getLength(); index++) {
			Node child = children.item(index);
			if (child instanceof Element element
					&& localName.equals(element.getLocalName())
					&& namespace.equals(element.getNamespaceURI())) {
				return text(element);
			}
		}
		return null;
	}

	private static String license(Element entry) {
		NodeList licenses = entry.getElementsByTagNameNS("*", "license");
		return licenses.getLength() == 0 ? null : text((Element) licenses.item(0));
	}

	private static List<ArxivLink> links(Element entry) {
		List<ArxivLink> links = new ArrayList<>();
		NodeList children = entry.getChildNodes();
		for (int index = 0; index < children.getLength(); index++) {
			Node child = children.item(index);
			if (child instanceof Element element
					&& "link".equals(element.getLocalName())
					&& ATOM_NAMESPACE.equals(element.getNamespaceURI())) {
				links.add(new ArxivLink(
						element.getAttribute("href"),
						element.getAttribute("rel"),
						element.getAttribute("type"),
						element.getAttribute("title")));
			}
		}
		return List.copyOf(links);
	}

	private static String text(Element element) {
		String value = element.getTextContent();
		return value == null || value.isBlank() ? null : value.strip();
	}
}

record ArxivFeed(List<ArxivFeedEntry> entries) {
}

record ArxivFeedEntry(
		String id,
		String title,
		String updated,
		String published,
		String license,
		List<ArxivLink> links) {
}

record ArxivLink(String href, String rel, String type, String title) {
}

final class ArxivFeedParseException extends RuntimeException {

	ArxivFeedParseException(String message, Throwable cause) {
		super(message, cause);
	}
}
