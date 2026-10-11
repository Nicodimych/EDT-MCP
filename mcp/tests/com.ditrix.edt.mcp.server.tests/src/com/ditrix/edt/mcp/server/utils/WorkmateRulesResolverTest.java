/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.utils.WorkmateRulesResolver.RulesResolutionException;

/**
 * Unit tests for {@link WorkmateRulesResolver}: standard 3-tier hierarchy, custom folders,
 * skill/command path indexing, and eager skill inlining.
 */
public class WorkmateRulesResolverTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testNoneOrOmittedProducesEmptyPreamble() throws Exception
    {
        Path userHome = tmp.newFolder("user").toPath(); //$NON-NLS-1$
        Files.createDirectories(userHome.resolve(".workmate")); //$NON-NLS-1$
        Files.writeString(userHome.resolve(".workmate/WORKMATE.md"), //$NON-NLS-1$
            "# User rules", StandardCharsets.UTF_8); //$NON-NLS-1$

        WorkmateRulesResolver resolver = new WorkmateRulesResolver(userHome, null);
        assertEquals("", resolver.resolvePreamble(null, Collections.emptyList(), (Path) null)); //$NON-NLS-1$
        assertEquals("", resolver.resolvePreamble("none", Collections.emptyList(), (Path) null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", resolver.resolvePreamble("  ", null, (Path) null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStandardHierarchyConcatenatesRulesAndOverridesSkills() throws Exception
    {
        Path userHome = tmp.newFolder("user").toPath(); //$NON-NLS-1$
        Path wsRoot = tmp.newFolder("workspace").toPath(); //$NON-NLS-1$
        Path projRoot = tmp.newFolder("project").toPath(); //$NON-NLS-1$

        // User level: WORKMATE.md + 2 skills (formy, samoproverka) + 1 command
        Path userWm = Files.createDirectories(userHome.resolve(".workmate")); //$NON-NLS-1$
        Files.writeString(userWm.resolve("WORKMATE.md"), "USER_RULE_TEXT", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        Path userFormy = Files.createDirectories(userWm.resolve("skills/formy")); //$NON-NLS-1$
        Files.writeString(userFormy.resolve("SKILL.md"), //$NON-NLS-1$
            "---\nname: formy\ndisplay_name: Формы (User)\n---\nUSER_FORMY_BODY", //$NON-NLS-1$
            StandardCharsets.UTF_8);
        Path userSamo = Files.createDirectories(userWm.resolve("skills/samoproverka")); //$NON-NLS-1$
        Files.writeString(userSamo.resolve("SKILL.md"), //$NON-NLS-1$
            "---\nname: samoproverka\ndescription: Самопроверка API\n---\nUSER_SAMOPROVERKA_BODY", //$NON-NLS-1$
            StandardCharsets.UTF_8);
        Path userCmds = Files.createDirectories(userWm.resolve("commands")); //$NON-NLS-1$
        Files.writeString(userCmds.resolve("проверить.md"), "CMD_BODY", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$

        // Project level: WORKMATE.md + overrides formy skill
        Path projWm = Files.createDirectories(projRoot.resolve(".workmate")); //$NON-NLS-1$
        Files.writeString(projWm.resolve("WORKMATE.md"), "PROJECT_RULE_TEXT", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        Path projFormy = Files.createDirectories(projWm.resolve("skills/formy")); //$NON-NLS-1$
        Files.writeString(projFormy.resolve("SKILL.md"), //$NON-NLS-1$
            "---\nname: formy\ndisplay_name: Формы (Project)\n---\nPROJECT_FORMY_BODY", //$NON-NLS-1$
            StandardCharsets.UTF_8);

        WorkmateRulesResolver resolver = new WorkmateRulesResolver(userHome, wsRoot);
        String preamble = resolver.resolvePreamble("standard", //$NON-NLS-1$
            Arrays.asList("formy", "samoproverka"), projRoot); //$NON-NLS-1$ //$NON-NLS-2$

        // Both User and Project WORKMATE.md are included, User before Project
        int userIdx = preamble.indexOf("USER_RULE_TEXT"); //$NON-NLS-1$
        int projIdx = preamble.indexOf("PROJECT_RULE_TEXT"); //$NON-NLS-1$
        assertTrue(userIdx > 0);
        assertTrue(projIdx > userIdx);

        // Project's formy skill overrides User's formy skill
        assertTrue(preamble.contains("PROJECT_FORMY_BODY")); //$NON-NLS-1$
        assertFalse(preamble.contains("USER_FORMY_BODY")); //$NON-NLS-1$
        assertTrue(preamble.contains("USER_SAMOPROVERKA_BODY")); //$NON-NLS-1$
        // YAML frontmatter was stripped from the inlined body
        assertFalse(preamble.contains("display_name: Формы (Project)")); //$NON-NLS-1$
        // Commands index is present with forward slashes
        assertTrue(preamble.contains("`проверить` -> `")); //$NON-NLS-1$
    }

    @Test
    public void testCustomDirectoryAndMissingSkillError() throws Exception
    {
        Path customDir = tmp.newFolder("custom-rules").toPath(); //$NON-NLS-1$
        Files.writeString(customDir.resolve("WORKMATE.md"), "CUSTOM_RULES", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        Path skillDir = Files.createDirectories(customDir.resolve("skills/skd")); //$NON-NLS-1$
        Files.writeString(skillDir.resolve("SKILL.md"), "SKD_SKILL_CONTENT", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$

        WorkmateRulesResolver resolver = new WorkmateRulesResolver(
            tmp.newFolder("empty-user").toPath(), null); //$NON-NLS-1$

        String preamble = resolver.resolvePreamble(customDir.toString(),
            Collections.singletonList("skd"), (Path) null); //$NON-NLS-1$
        assertTrue(preamble.contains("CUSTOM_RULES")); //$NON-NLS-1$
        assertTrue(preamble.contains("SKD_SKILL_CONTENT")); //$NON-NLS-1$

        try
        {
            resolver.resolvePreamble(customDir.toString(),
                Collections.singletonList("unknown-skill"), (Path) null); //$NON-NLS-1$
            fail("Expected RulesResolutionException for missing skill"); //$NON-NLS-1$
        }
        catch (RulesResolutionException expected)
        {
            assertTrue(expected.getMessage().contains("unknown-skill")); //$NON-NLS-1$
            assertTrue("Error lists available skills", expected.getMessage().contains("skd")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
