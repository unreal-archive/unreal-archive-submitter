package org.unrealarchive.submitter.submit;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record CollectionSubmission(String title, String description, String author, Map<String, String> links, String image,
								   LocalDate createdDate, List<CollectionItem> items) {

	public CollectionSubmission {
		if (links == null) links = Map.of();
		if (items == null) items = List.of();
	}

	public static record CollectionItem(String reference, String title) {}
}
