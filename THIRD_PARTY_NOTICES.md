# Third-party components

The project's Apache-2.0 license covers project code to the extent its authors are authorized to license it. It does not replace licenses of separately distributed dependencies, bundled third-party code, or third-party assets. Existing notices in individual files are retained.

- Maven Wrapper scripts retain their Apache-2.0 file headers. See [Apache Maven Wrapper](https://maven.apache.org/wrapper/).
- Java dependencies are declared in the Maven POM files. Frontend dependencies and license metadata are recorded in `web/package-lock.json`; installed packages include their own license files.
- MySQL Connector/J 8.3.0 is declared as a runtime dependency. Its license is GPLv2 with the Universal FOSS Exception v1.0; see the [upstream version's license](https://github.com/mysql/mysql-connector-j/blob/8.3.0/LICENSE). Distribution of a bundled JAR or container must preserve applicable third-party notices and obligations.
- Mock avatar URLs use the external DiceBear API. Those images are not bundled in this source release; anyone using or redistributing generated artwork should review the selected style's license and service terms.
- The Snowflake utility identifies the Snowflake algorithm in its source comment. Algorithm attribution does not imply authorship of an upstream project.

This source release does not include dependency archives, compiled applications, generated avatar artwork or container images. Before distributing a binary package, collect the actual resolved dependencies' license and notice files for that package.
