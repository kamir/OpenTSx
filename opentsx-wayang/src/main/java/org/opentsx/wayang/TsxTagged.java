package org.opentsx.wayang;

import org.opentsx.model.v2.Episode;

/**
 * An episode marked with the input of an {@code align.vector} node it belongs to. Wayang operators have one or two
 * inputs; the inputs of a vector are therefore marked and joined with a union before {@link TsxAlignVector}.
 */
public record TsxTagged(String input, Episode episode) {
}
