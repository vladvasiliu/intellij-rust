/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

class RsMoveMultipleFilesTest : RsMoveFileTestBase() {

    fun `test simple`() = doTest(
        arrayOf("mod1/foo1.rs", "mod1/foo2.rs"),
        "mod2",
        """
    //- main.rs
        mod mod1;
        mod mod2;
    //- mod1/mod.rs
        mod foo1;
        mod foo2;
    //- mod2.rs
    //- mod1/foo1.rs
        fn func1() {}
    //- mod1/foo2.rs
        fn func2() {}
    """, """
    //- main.rs
        mod mod1;
        mod mod2;
    //- mod1/mod.rs
    //- mod2.rs
        mod foo1;
        mod foo2;
    //- mod2/foo1.rs
        fn func1() {}
    //- mod2/foo2.rs
        fn func2() {}
    """)

    fun `test move directory and file`() = doTest(
        arrayOf("mod1/foo1", "mod1/foo2.rs"),
        "mod2",
        """
    //- main.rs
        mod mod1;
        mod mod2;
    //- mod1/mod.rs
        mod foo1;
        mod foo2;
    //- mod2.rs
    //- mod1/foo1/mod.rs
        fn func1() {}
    //- mod1/foo2.rs
        fn func2() {}
    """, """
    //- main.rs
        mod mod1;
        mod mod2;
    //- mod1/mod.rs
    //- mod2.rs
        mod foo1;
        mod foo2;
    //- mod2/foo1/mod.rs
        fn func1() {}
    //- mod2/foo2.rs
        fn func2() {}
    """)

    fun `test move files with cross self references`() = doTest(
        arrayOf("mod1/foo1.rs", "mod1/foo2.rs"),
        "mod2",
        """
    //- main.rs
        mod mod1;
        mod mod2;
    //- mod1/mod.rs
        mod foo1;
        mod foo2;
    //- mod2.rs
    //- mod1/foo1.rs
        pub fn func1() {
            crate::mod1::foo2::func2();
        }
    //- mod1/foo2.rs
        pub fn func2() {
            crate::mod1::foo1::func1();
        }
    """, """
    //- main.rs
        mod mod1;
        mod mod2;
    //- mod1/mod.rs
    //- mod2.rs
        mod foo1;
        mod foo2;
    //- mod2/foo1.rs
        pub fn func1() {
            crate::mod2::foo2::func2();
        }
    //- mod2/foo2.rs
        pub fn func2() {
            crate::mod2::foo1::func1();
        }
    """)
}
