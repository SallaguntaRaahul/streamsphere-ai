package ai.streamsphere.common.web;

import java.util.List;

public record PageResponse<T>(List<T> items, String nextCursor) {}
