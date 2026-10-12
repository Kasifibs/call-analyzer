package callanalyzer.events;

import callanalyzer.model.MethodRef;

/**
 * An observer registering itself (or being registered) with the dispatcher.
 *
 * @param eventName     the event name it is registered for; {@code null} for registrations without a name or when the
 *                      name cannot be determined statically
 * @param eventType     for registrations without a name ({@code addObserver(this)}): the event class the observer
 *                      handles, taken from {@code IObserver<AEvent>}; otherwise {@code null}
 * @param observerClass the observer class (internal name)
 * @param registeredIn  the method containing the register call
 * @param sourceFile    source file of that method, may be {@code null}
 * @param line          line of the register call
 */
public record Registration(String eventName, String eventType, String observerClass, MethodRef registeredIn,
                           String sourceFile, int line) {
}
