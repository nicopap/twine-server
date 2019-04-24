# Twine Server

Twine server is an extremely basic Scala backend to support the Twine2
nonlinear story editor.

It provides cloud persistance and shared files for twine stories. It also
implements a basic lock mechanism to prevent overwritting other people's work
and sidestep the complex problem of diff merging or real-time multi-user
editing.

## Building

The project uses the standard [sbt](https://www.scala-sbt.org/) tool for
building and debugging.

**The initial commit doesn't have source code** (because it doesn't work) so
please be patient.

## Documentation

the `documentation` file contains an overview of the API and design decisions.
The project aims to be minimal, but documenting since the beginning is always a
good idea.

The doc is written in the [Zim wiki](https://zim-wiki.org/) format.
Zim  is the prefered tool for reading the doc, however, the wiki format is
already quite readable in plain text.

## Licenses

(c) Nicola Papale, licensed under AGPL for the source code and Creative Commons
Attribution-ShareAlike 3.0 for the documentation.
See the AGPL file at the root of the git tree and
<https://creativecommons.org/licenses/by-sa/3.0/legalcode> for further
informations.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
