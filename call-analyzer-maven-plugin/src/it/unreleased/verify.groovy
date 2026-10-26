// rent() acquires Bmw and never releases it; rentSafely() releases it in a finally block; charge() only uses the
// annotation, which needs no release.
String text = new File(basedir, "build.log").text
assert text.contains("[R] com.example.RentalService#rent()  release edilmeyen: Bmw (satir 11)")
assert !text.contains("[R] com.example.RentalService#rentSafely()")
assert !text.contains("[R] com.example.RentalService#charge()")
assert text.contains("call-analyzer: 3 metot: 0 kesin eksik, 0 olasi eksik, 1 release eksik, 2 OK")
assert text.contains("call-analyzer: 1 metotta acquire edilip release edilmeyen var. Ayrintilar:")
return true
