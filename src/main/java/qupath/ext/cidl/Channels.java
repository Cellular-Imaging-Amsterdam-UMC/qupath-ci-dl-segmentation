package qupath.ext.cidl;

import qupath.lib.images.servers.ImageServer;

import java.util.List;
import java.util.Locale;

record Channels(Choice primary, Choice secondary) {
    static final Choice NONE = new Choice(-1, "None");

    record Choice(int index, String name) {
        @Override
        public String toString() {
            return index < 0 ? name : (index + 1) + " · " + name;
        }
    }

    static List<Choice> choices(ImageServer<?> server) {
        return choices(server.getMetadata());
    }

    static List<Choice> choices(qupath.lib.images.servers.ImageServerMetadata metadata) {
        var channels = metadata.getChannels();
        return java.util.stream.IntStream.range(0, channels.size())
                .mapToObj(i -> new Choice(i, channels.get(i).getName())).toList();
    }

    static Choice initial(List<Choice> choices) {
        return choices.stream().filter(choice -> choice.name().toLowerCase(Locale.ROOT).contains("dapi"))
                .findFirst().orElse(choices.isEmpty() ? null : choices.getFirst());
    }

    List<Integer> resolve(ImageServer<?> server) {
        var choices = choices(server);
        int first = resolve(choices, primary);
        if (secondary == null || secondary.index() < 0)
            return List.of(first);
        int second = resolve(choices, secondary);
        if (first == second)
            throw new IllegalArgumentException("Choose different primary and secondary channels.");
        return List.of(first, second);
    }

    private static int resolve(List<Choice> choices, Choice choice) {
        if (choice == null)
            throw new IllegalArgumentException("Choose an image channel.");
        if (choice.index() < choices.size() && choices.get(choice.index()).name().equals(choice.name()))
            return choice.index();
        var matches = choices.stream().filter(channel -> channel.name().equals(choice.name())).toList();
        if (matches.size() == 1)
            return matches.getFirst().index();
        throw new IllegalArgumentException("Could not match channel “" + choice.name() + "” in this image.");
    }
}
