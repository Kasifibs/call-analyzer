package sample.cars;

/** Default (CLASS) retention: stored in the class file but not visible at runtime. Must still be found. */
public @interface LegacyAcquireCar {
    Class<?>[] value();
}
