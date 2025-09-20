/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import constants.game.NpcChat;
import constants.id.NpcId;
import server.ThreadManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LookupMapCommand extends Command {
    {
        setDescription("Lookup map ids by name.");
    }

	private static final int MAX_SEARCH_HITS = 100;
	private static final String MAP_JSON_FILE_PATH = "handbook/json/Map.json";

    private static volatile HandbookFileItems cachedItems;

    private static class HandbookFileItems {
        private final List<HandbookItem> items;

		public HandbookFileItems(List<String> fileLines) {
			this.items = fileLines.stream()
					.map(this::parseJsonLine)
					.filter(Predicate.not(Objects::isNull))
					.toList();
		}

		private static final Pattern JSON_ENTRY_PATTERN = Pattern.compile("^\\s*\"(\\d+)\"\\s*:\\s*\"(.*)\"\\s*,?\\s*$");

		private HandbookItem parseJsonLine(String line) {
            if (line == null) {
                return null;
            }

			// Ignore braces
			String trimmed = line.trim();
			if (trimmed.equals("{") || trimmed.equals("}")) {
				return null;
			}

			Matcher m = JSON_ENTRY_PATTERN.matcher(line);
			if (!m.matches()) {
				return null;
			}
			String id = m.group(1);
			String name = m.group(2);
			// Basic unescape for common JSON escapes
			name = name.replace("\\\"", "\"").replace("\\\\", "\\");
			return new HandbookItem(id, name);
        }

        public List<HandbookItem> search(String query) {
            if (query == null || query.isBlank()) {
                return Collections.emptyList();
            }
            return items.stream()
                    .filter(item -> item.matches(query))
                    .toList();
        }
    }

    private record HandbookItem(String id, String name) {
        public HandbookItem {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
        }

        public boolean matches(String query) {
            if (query == null) {
                return false;
            }
            return this.name.toLowerCase().contains(query.toLowerCase());
        }
    }

    @Override
    public void execute(Client client, final String[] params) {
        final Character chr = client.getPlayer();
        if (params.length < 1) {
            chr.yellowMessage("Syntax: !lookupmap <search string>");
            return;
        }

        final String query = String.join(" ", params);
		chr.yellowMessage("Querying map list (JSON)... This may take some time. Refine your search if needed.");

        Runnable queryRunnable = () -> {
            try {
                ensureLoaded();

                final List<HandbookItem> searchHits = cachedItems.search(query);

                if (!searchHits.isEmpty()) {
                    String searchHitsText = searchHits.stream()
                            .limit(MAX_SEARCH_HITS)
                            .map(item -> "#b" + item.id + "#k - " + item.name)
                            .collect(Collectors.joining(NpcChat.NEW_LINE));
                    int hitsCount = Math.min(searchHits.size(), MAX_SEARCH_HITS);
                    String summaryText = "Results found: #r" + searchHits.size() + "#k | Returned: #b" + hitsCount + "#k/100 | Refine search to improve time.";
                    String fullText = searchHitsText + NpcChat.NEW_LINE + summaryText;
                    chr.getAbstractPlayerInteraction().npcTalk(NpcId.MAPLE_ADMINISTRATOR, fullText);
                } else {
                    chr.yellowMessage("No maps found matching: " + query);
                }
			} catch (IOException e) {
				chr.yellowMessage("Error reading Map.json, please contact your administrator.");
            }
        };

        ThreadManager.getInstance().newTask(queryRunnable);
    }

    private static void ensureLoaded() throws IOException {
        if (cachedItems != null) {
            return;
        }
			synchronized (LookupMapCommand.class) {
            if (cachedItems == null) {
					final List<String> fileLines = Files.readAllLines(Path.of(MAP_JSON_FILE_PATH));
                cachedItems = new HandbookFileItems(fileLines);
            }
        }
    }
}


