def files = [
	"target/dependency-builder/checkout/com.example:simple:pom:0.0.1:runtime/invoker.properties",
	"target/dependency-builder/checkout/com.example:simple:pom:0.0.1:runtime/pom.xml",
];

for (def file : files) {
	def touchFile = new File(basedir, file);
	println "Checking for existence: " + touchFile;
	assert touchFile.isFile();
}