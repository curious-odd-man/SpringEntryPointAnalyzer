# Simple tool to anlyzes unknown Spring Boot application

## Motivation

When you face a new Spring Boot application code - you might struggle finding all the "entry points" of an application.

There are various annotations and interfaces that will trigger code execution withing a spring boot application.

This simple app scans all source files and attempts to find them all and list grouped result.

## What exactly is looked for?

The constants are defined here: [SpringEntryPointAnalyzer.java](src/main/java/com/github/curiousoddman/tools/sping/analyzer/SpringEntryPointAnalyzer.java)

## How to run:

Just run a main method providing a path to a src directory you want to scan.

## Example output:

For example, if I run this app on https://github.com/spring-projects/spring-petclinic project, I see following output:

![docs/img.png](docs/img.png)



