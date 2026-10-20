File log = new File(basedir, "build.log")
assert log.text.contains("call-analyzer: 2 metot: 0 kesin eksik")
assert new File(basedir, "target/call-analyzer/tarama.html").isFile()
assert new File(basedir, "target/call-analyzer/tarama.txt").isFile()
assert new File(basedir, "target/call-analyzer/tarama.json").isFile()
assert new File(basedir, "target/call-analyzer/tarama_metotlar").isDirectory()
return true
