Vignette
========

Vignette is a simple CLI application for remapping Java artifacts,
using 'de-obfuscation' mappings - built using [Atlas] and [Lorenz].

## Usage

To see how to use Vignette run:
```
java jar vignette.jar --help 
```

Common:
```
java -jar vignette.jar -f tsrg -m mappings.tsrg -i in.jar -o out.jar
```

## Capabilities

As main, Vignette will rename class entries (with fields, methods and parameters) inside given JAR archive.

Also JAR manifest will be transformed by updating manifest `Main-Class` entry. All digest signature entries will be removed.

See the source code for more information.


### Deducing parameters name

Vignete can deduce parameters name from parameter type class name based on parameter descriptor available in parent method.

```
java -jar vignette.jar --deduce-param-names -f tsrg -m mappings.tsrg -i in.jar -o out.jar
```

The deduced parameter name will be used when the loaded name mappings (SRG's) do not provide a mapping for the given function parameter.

### Dictionary

Special dictionaries can be used to modify deducing process output and by this resulting parameter names.

```
Dictionary rule format:
    PATTERN:FILTER ACTION:VALUE     # means do ACTION when PATTERN and FILTER matches

or shorter forms like:
    PATTERN VALUE                   # means replace
    PATTERN ACTION:VALUE            # means do ACTION
    PATTERN:FILTER VALUE            # means replace with package filter

where:
    PATTERN regex_expression 
        Regular expression to match parameter type class name
    FILTER regex_expression
        Regular expression to match parameter type class package
    ACTION one of [RENAME, PREFIX, SUFFIX, FIRST, LAST]
        Action is type of activity to do when rule matches pattern and filter
    VALUE string
        Used in RENAME, SUFFIX and PREFIX action types
    # comment
        Just comment, ignored by process
```

Example dictionary file:
```
#
# Replace primitives
#
^Z$ flag       # boolean
^C$ c          # char
^B$ b          # byte
^S$ i          # short
^I$ i          # int
^J$ i          # long
^F$ f          # float
^D$ d          # double
^V$ nothing    # void
^String$ s     # Convert long 'string' to just 's'
```

Use dictionary while deducing:

```
java -jar vignette.jar --deduce-param-names --dictionary dictionary.dict -f tsrg -m mappings.tsrg -i in.jar -o out.jar
```


## License

Vignette is made available under the terms of the Mozilla Public
License 2.0, giving you the freedom to use, copy, and distribute
Vignette to others, in addition to the right to distribute modified
versions.

[Atlas]: https://github.com/CadixDev/Atlas
[Lorenz]: https://github.com/CadixDev/Lorenz
