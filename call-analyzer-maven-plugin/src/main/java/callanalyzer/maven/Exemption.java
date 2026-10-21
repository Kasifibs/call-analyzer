package callanalyzer.maven;

import java.util.List;

/**
 * One {@code <exemption>} of the plugin configuration: classes {@link #method} may use without acquiring them.
 * <pre>{@code
 * <exemption>
 *     <method>com.example.RentalService#rent</method>
 *     <classes>
 *         <class>com.example.TeslaRepository</class>
 *     </classes>
 * </exemption>
 * }</pre>
 */
public class Exemption {

    /** Method selector, e.g. {@code com.example.RentalService#rent}; a class name alone means all its methods. */
    private String method;

    private List<String> classes;

    public String getMethod() {
        return method;
    }

    public List<String> getClasses() {
        return classes;
    }
}
