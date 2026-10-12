package callanalyzer.dataflow;

import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Value;

import java.util.Set;

/**
 * A value in the data-flow analysis: the basic JVM value (for sizes and merging) plus every place it may come from.
 */
public record TrackedValue(BasicValue basic, Set<ValueSource> sources) implements Value {

    @Override
    public int getSize() {
        return basic.getSize();
    }
}
