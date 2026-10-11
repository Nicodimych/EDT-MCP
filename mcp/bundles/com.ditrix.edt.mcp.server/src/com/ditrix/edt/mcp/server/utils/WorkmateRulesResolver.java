/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

/**
 * Resolves {@code .workmate/WORKMATE.md} rules and {@code skills/<name>/SKILL.md} /
 * {@code commands/<name>.md} files for {@code ask_workmate} and {@code workmate_chat_*} tools.
 * <p>
 * Mirrors 1C:Workmate's own 3-tier location hierarchy ({@code User -> Workspace -> Project}) when
 * {@code rulesSource="standard"} is selected, or reads from a caller-supplied custom directory when
 * {@code rulesSource} is a filesystem path. When {@code rulesSource} is omitted or {@code "none"}
 * and no explicit {@code skills} are requested, nothing is added.
 */
public final class WorkmateRulesResolver
{
    /** Canonical token for EDT's standard 3-tier hierarchy (User + Workspace + Project). */
    public static final String SOURCE_STANDARD = "standard"; //$NON-NLS-1$

    /** Canonical token that disables automatic rules/catalogue loading. */
    public static final String SOURCE_NONE = "none"; //$NON-NLS-1$

    private static final String WORKMATE_DIR = ".workmate"; //$NON-NLS-1$
    private static final String WORKMATE_MD = "WORKMATE.md"; //$NON-NLS-1$
    private static final String SKILLS_DIR = "skills"; //$NON-NLS-1$
    private static final String COMMANDS_DIR = "commands"; //$NON-NLS-1$
    private static final String SKILL_MD = "SKILL.md"; //$NON-NLS-1$

    private final Path userHomeOverride;
    private final Path workspaceRootOverride;

    /** Creates a resolver using the live JVM user home and Eclipse workspace root. */
    public WorkmateRulesResolver()
    {
        this(null, null);
    }

    /**
     * Package-private constructor for deterministic unit tests without an OSGi workspace.
     *
     * @param userHomeOverride optional override for {@code user.home}
     * @param workspaceRootOverride optional override for the Eclipse workspace root directory
     */
    public WorkmateRulesResolver(Path userHomeOverride, Path workspaceRootOverride)
    {
        this.userHomeOverride = userHomeOverride;
        this.workspaceRootOverride = workspaceRootOverride;
    }

    /**
     * Normalizes a {@code rulesSource} argument: returns {@code null} when omitted, blank, or
     * {@code "none"}; returns {@link #SOURCE_STANDARD} for {@code "standard"}, {@code "default"},
     * {@code "auto"}, or {@code "edt"}; otherwise returns the trimmed custom directory path.
     *
     * @param raw the raw parameter value (may be {@code null})
     * @return normalized rulesSource, or {@code null} when disabled
     */
    public static String normalizeRulesSource(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty())
        {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (SOURCE_NONE.equals(lower) || "off".equals(lower) || "false".equals(lower)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        if (SOURCE_STANDARD.equals(lower) || "default".equals(lower) //$NON-NLS-1$
            || "auto".equals(lower) || "edt".equals(lower)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return SOURCE_STANDARD;
        }
        return trimmed;
    }

    /**
     * Normalizes and deduplicates a list of skill names, preserving order.
     *
     * @param rawSkills raw list of skill names (may be {@code null})
     * @return immutable list of trimmed non-empty skill names (never {@code null})
     */
    public static List<String> normalizeSkills(List<String> rawSkills)
    {
        if (rawSkills == null || rawSkills.isEmpty())
        {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String item : rawSkills)
        {
            if (item != null)
            {
                String trimmed = item.trim();
                if (!trimmed.isEmpty())
                {
                    unique.add(trimmed);
                }
            }
        }
        return unique.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(unique));
    }

    /**
     * Merges chat-level default skills with turn-level skills, preserving order and avoiding
     * duplicates.
     *
     * @param first first skill list (may be {@code null})
     * @param second second skill list (may be {@code null})
     * @return merged immutable list
     */
    public static List<String> mergeSkills(List<String> first, List<String> second)
    {
        List<String> combined = new ArrayList<>();
        if (first != null)
        {
            combined.addAll(first);
        }
        if (second != null)
        {
            combined.addAll(second);
        }
        return normalizeSkills(combined);
    }

    /**
     * Validates {@code rulesSource} and {@code skills} and builds the preamble block to prepend
     * before the user's message/question. Returns an empty string when neither {@code rulesSource}
     * nor {@code skills} is active.
     *
     * @param rulesSource raw or normalized {@code rulesSource} ({@code null}, {@code "standard"},
     *        or a custom directory path)
     * @param skills optional list of skill names to inline in full
     * @param project optional open EDT project for project-level {@code .workmate} resolution
     * @return the formatted rules/skills block ending with {@code "\n\n"}, or {@code ""} when
     *         nothing is requested
     * @throws RulesResolutionException when a custom {@code rulesSource} path does not exist or a
     *         requested skill name cannot be found
     */
    public String resolvePreamble(String rulesSource, List<String> skills, IProject project)
        throws RulesResolutionException
    {
        return resolvePreamble(rulesSource, true, skills, projectLocation(project));
    }

    /**
     * Resolves the preamble with explicit control over whether {@code WORKMATE.md} and the
     * skill/command directory index are emitted, or only the explicitly requested {@code skills}
     * (used on follow-up turns of a chat where {@code WORKMATE.md} was already sent on turn 1).
     *
     * @param rulesSource raw or normalized {@code rulesSource} used to locate rules and skills
     * @param includeRulesAndCatalogue {@code true} to include {@code WORKMATE.md} and the
     *        skill/command index; {@code false} to inline only {@code skills}
     * @param skills optional list of skill names to inline
     * @param project optional open EDT project
     * @return the formatted rules/skills block ending with {@code "\n\n"}, or {@code ""}
     * @throws RulesResolutionException when validation or resolution fails
     */
    public String resolvePreamble(String rulesSource, boolean includeRulesAndCatalogue,
        List<String> skills, IProject project) throws RulesResolutionException
    {
        return resolvePreamble(rulesSource, includeRulesAndCatalogue, skills,
            projectLocation(project));
    }

    /**
     * Path-based overload of {@link #resolvePreamble(String, List, IProject)} for unit testing and
     * callers that already resolved the project directory.
     *
     * @param rulesSource raw or normalized {@code rulesSource}
     * @param skills optional list of skill names to inline
     * @param projectRoot optional project root directory on disk
     * @return the formatted rules/skills block ending with {@code "\n\n"}, or {@code ""}
     * @throws RulesResolutionException when validation or resolution fails
     */
    public String resolvePreamble(String rulesSource, List<String> skills, Path projectRoot)
        throws RulesResolutionException
    {
        return resolvePreamble(rulesSource, true, skills, projectRoot);
    }

    /**
     * Path-based overload of {@link #resolvePreamble(String, boolean, List, IProject)}.
     *
     * @param rulesSource raw or normalized {@code rulesSource}
     * @param includeRulesAndCatalogue whether to include {@code WORKMATE.md} and the skill index
     * @param skills optional list of skill names to inline
     * @param projectRoot optional project root directory on disk
     * @return the formatted rules/skills block ending with {@code "\n\n"}, or {@code ""}
     * @throws RulesResolutionException when validation or resolution fails
     */
    public String resolvePreamble(String rulesSource, boolean includeRulesAndCatalogue,
        List<String> skills, Path projectRoot) throws RulesResolutionException
    {
        String normalizedSource = normalizeRulesSource(rulesSource);
        List<String> normalizedSkills = normalizeSkills(skills);
        boolean emitRules = includeRulesAndCatalogue && normalizedSource != null;
        if (!emitRules && normalizedSkills.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }

        List<LabeledRoot> ruleRoots = new ArrayList<>();
        List<LabeledRoot> skillSearchRoots = new ArrayList<>();

        if (normalizedSource != null && !SOURCE_STANDARD.equals(normalizedSource))
        {
            Path customRoot = resolveCustomWorkmateRoot(normalizedSource);
            LabeledRoot customLabeled = new LabeledRoot("Custom", customRoot); //$NON-NLS-1$
            ruleRoots.add(customLabeled);
            skillSearchRoots.add(customLabeled);
            // Also allow explicit skills to fall back to standard roots (Project -> Workspace -> User)
            // if a custom rules directory only overrides a subset of skills.
            List<LabeledRoot> standardAsc = collectStandardRoots(projectRoot);
            for (int i = standardAsc.size() - 1; i >= 0; i--)
            {
                addIfAbsent(skillSearchRoots, standardAsc.get(i));
            }
        }
        else
        {
            // Standard hierarchy:
            // - WORKMATE.md rules are concatenated from general to specific: User -> Workspace -> Project
            // - Skills override from specific to general: Project -> Workspace -> User
            List<LabeledRoot> standardAsc = collectStandardRoots(projectRoot);
            if (SOURCE_STANDARD.equals(normalizedSource))
            {
                ruleRoots.addAll(standardAsc);
            }
            for (int i = standardAsc.size() - 1; i >= 0; i--)
            {
                skillSearchRoots.add(standardAsc.get(i));
            }
        }

        // 1. Validate and load explicitly requested skills first so missing skills fail fast.
        Map<String, ResolvedSkill> inlinedSkills = new LinkedHashMap<>();
        for (String skillName : normalizedSkills)
        {
            validateSkillName(skillName);
            ResolvedSkill resolved = findSkillOrCommand(skillName, skillSearchRoots);
            if (resolved == null)
            {
                throw new RulesResolutionException(buildMissingSkillMessage(skillName,
                    skillSearchRoots));
            }
            inlinedSkills.put(skillName, resolved);
        }

        // 2. Collect WORKMATE.md contents and available skills/commands catalogue when requested.
        List<LoadedRuleFile> loadedRules = new ArrayList<>();
        Map<String, DiscoveredEntry> discoveredSkills = new TreeMap<>();
        Map<String, DiscoveredEntry> discoveredCommands = new TreeMap<>();

        if (emitRules)
        {
            for (LabeledRoot root : ruleRoots)
            {
                Path rulesFile = root.path.resolve(WORKMATE_MD);
                if (Files.isRegularFile(rulesFile))
                {
                    String content = readUtf8(rulesFile);
                    if (!content.isBlank())
                    {
                        loadedRules.add(new LoadedRuleFile(root.label, rulesFile, content.trim()));
                    }
                }
                collectSkillsInRoot(root.path, discoveredSkills);
                collectCommandsInRoot(root.path, discoveredCommands);
            }
        }

        if (loadedRules.isEmpty() && discoveredSkills.isEmpty() && discoveredCommands.isEmpty()
            && inlinedSkills.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }

        StringBuilder out = new StringBuilder();
        out.append("=== ПРАВИЛА И НАВЫКИ WORKMATE (.workmate) ===\n"); //$NON-NLS-1$
        out.append("Примечание среды EDT:\n"); //$NON-NLS-1$
        out.append("- Для чтения любого файла навыка (`SKILL.md`) или команды из каталога ниже ") //$NON-NLS-1$
            .append("используй свой нативный инструмент `Read` с указанным абсолютным путём `path`.\n"); //$NON-NLS-1$
        out.append("- Навыки из раздела «Подключённые навыки» ниже уже загружены в этот промпт ") //$NON-NLS-1$
            .append("целиком — повторно вызывать для них `Read` не нужно.\n"); //$NON-NLS-1$
        out.append("- Для проверки маркеров/ошибок проекта используй нативный инструмент `GetMarkers` ") //$NON-NLS-1$
            .append("(или инструменты EDT-MCP через JShell-мост `edt.mcp.bridge=v1`).\n"); //$NON-NLS-1$
        out.append("- Имена инструментов `Skill`, `SlashCommand`, `TodoWrite`, `Task` и префиксы ") //$NON-NLS-1$
            .append("`mcp__*` в текстах правил относятся к CLI-среде: в EDT вместо `Skill` читай ") //$NON-NLS-1$
            .append("файл навыка через `Read`, инструменты EDT-MCP (`get_platform_documentation`, ") //$NON-NLS-1$
            .append("`validate_form_model`, `get_problem_summary` и др.) вызывай через JShell-мост ") //$NON-NLS-1$
            .append("`edt.mcp.bridge=v1`, а шаги плана выполняй последовательно в текущем диалоге.\n\n"); //$NON-NLS-1$

        for (LoadedRuleFile ruleFile : loadedRules)
        {
            out.append("--- WORKMATE.md [").append(ruleFile.label).append(": ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(slashPath(ruleFile.path)).append("] ---\n") //$NON-NLS-1$
                .append(ruleFile.content).append("\n\n"); //$NON-NLS-1$
        }

        if (!discoveredSkills.isEmpty())
        {
            out.append("### Доступные файлы навыков (для чтения через `Read` по полю `path`)\n"); //$NON-NLS-1$
            for (DiscoveredEntry entry : discoveredSkills.values())
            {
                out.append("- `").append(entry.name).append("` -> `") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(slashPath(entry.path)).append("`"); //$NON-NLS-1$
                if (inlinedSkills.containsKey(entry.name))
                {
                    out.append(" *(уже подключён ниже)*"); //$NON-NLS-1$
                }
                else if (entry.summary != null && !entry.summary.isEmpty())
                {
                    out.append(" — ").append(entry.summary); //$NON-NLS-1$
                }
                out.append("\n"); //$NON-NLS-1$
            }
            out.append("\n"); //$NON-NLS-1$
        }

        if (!discoveredCommands.isEmpty())
        {
            out.append("### Доступные файлы команд (для чтения через `Read` по полю `path`)\n"); //$NON-NLS-1$
            for (DiscoveredEntry entry : discoveredCommands.values())
            {
                out.append("- `").append(entry.name).append("` -> `") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(slashPath(entry.path)).append("`\n"); //$NON-NLS-1$
            }
            out.append("\n"); //$NON-NLS-1$
        }

        if (!inlinedSkills.isEmpty())
        {
            out.append("=== ПОДКЛЮЧЁННЫЕ НАВЫКИ (ОБЯЗАТЕЛЬНЫ К ВЫПОЛНЕНИЮ) ===\n\n"); //$NON-NLS-1$
            for (ResolvedSkill skill : inlinedSkills.values())
            {
                out.append("--- Навык `").append(skill.name).append("` [") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(slashPath(skill.path)).append("] ---\n") //$NON-NLS-1$
                    .append(skill.body).append("\n\n"); //$NON-NLS-1$
            }
        }

        out.append("=== КОНЕЦ ПРАВИЛ И НАВЫКОВ WORKMATE ===\n\n"); //$NON-NLS-1$
        return out.toString();
    }

    private Path resolveCustomWorkmateRoot(String customSource) throws RulesResolutionException
    {
        final Path path;
        try
        {
            path = Paths.get(customSource).toAbsolutePath().normalize();
        }
        catch (InvalidPathException e)
        {
            throw new RulesResolutionException("Invalid rulesSource path '" + customSource //$NON-NLS-1$
                + "': " + e.getMessage() + ". Pass rulesSource='standard', omit it, or provide a " //$NON-NLS-1$ //$NON-NLS-2$
                + "valid directory path on disk."); //$NON-NLS-1$
        }
        if (!Files.isDirectory(path))
        {
            throw new RulesResolutionException("rulesSource directory '" + slashPath(path) //$NON-NLS-1$
                + "' does not exist or is not a directory. Pass rulesSource='standard', omit it, " //$NON-NLS-1$
                + "or provide an existing directory path."); //$NON-NLS-1$
        }
        // If the caller pointed at a project/parent directory that contains a .workmate subfolder
        // and does not directly contain WORKMATE.md or skills/, step into .workmate automatically.
        Path nestedWorkmate = path.resolve(WORKMATE_DIR);
        boolean hasDirectArtifacts = Files.isRegularFile(path.resolve(WORKMATE_MD))
            || Files.isDirectory(path.resolve(SKILLS_DIR))
            || Files.isDirectory(path.resolve(COMMANDS_DIR));
        if (!hasDirectArtifacts && Files.isDirectory(nestedWorkmate))
        {
            return nestedWorkmate;
        }
        return path;
    }

    private List<LabeledRoot> collectStandardRoots(Path projectRoot)
    {
        List<LabeledRoot> result = new ArrayList<>(3);
        Path userHome = resolveUserHome();
        if (userHome != null)
        {
            Path userWorkmate = userHome.resolve(WORKMATE_DIR).toAbsolutePath().normalize();
            if (Files.isDirectory(userWorkmate))
            {
                addIfAbsent(result, new LabeledRoot("User", userWorkmate)); //$NON-NLS-1$
            }
        }

        Path workspaceRoot = resolveWorkspaceRoot();
        if (workspaceRoot != null)
        {
            Path wsWorkmate = workspaceRoot.resolve(WORKMATE_DIR).toAbsolutePath().normalize();
            if (Files.isDirectory(wsWorkmate))
            {
                addIfAbsent(result, new LabeledRoot("Workspace", wsWorkmate)); //$NON-NLS-1$
            }
        }

        if (projectRoot != null)
        {
            Path projWorkmate = projectRoot.resolve(WORKMATE_DIR).toAbsolutePath().normalize();
            if (Files.isDirectory(projWorkmate))
            {
                addIfAbsent(result, new LabeledRoot("Project", projWorkmate)); //$NON-NLS-1$
            }
        }
        return result;
    }

    private static void addIfAbsent(List<LabeledRoot> target, LabeledRoot candidate)
    {
        for (LabeledRoot existing : target)
        {
            if (existing.path.equals(candidate.path))
            {
                return;
            }
        }
        target.add(candidate);
    }

    private Path resolveUserHome()
    {
        if (userHomeOverride != null)
        {
            return userHomeOverride;
        }
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        if (home == null || home.isBlank())
        {
            return null;
        }
        try
        {
            return Paths.get(home);
        }
        catch (InvalidPathException e)
        {
            return null;
        }
    }

    private Path resolveWorkspaceRoot()
    {
        if (workspaceRootOverride != null)
        {
            return workspaceRootOverride;
        }
        try
        {
            if (ResourcesPlugin.getWorkspace() != null
                && ResourcesPlugin.getWorkspace().getRoot() != null)
            {
                IPath loc = ResourcesPlugin.getWorkspace().getRoot().getLocation();
                if (loc != null)
                {
                    return loc.toFile().toPath();
                }
            }
        }
        catch (Throwable ignored) // NOSONAR headless / non-OSGi unit tests
        {
            // Ignore when workspace is not running
        }
        return null;
    }

    private static Path projectLocation(IProject project)
    {
        if (project == null)
        {
            return null;
        }
        try
        {
            IPath location = project.getLocation();
            return location != null ? location.toFile().toPath() : null;
        }
        catch (Throwable ignored) // NOSONAR defensive guard for mock/closed projects
        {
            return null;
        }
    }

    private static void validateSkillName(String skillName) throws RulesResolutionException
    {
        if (skillName.contains("..") || skillName.contains("/") || skillName.contains("\\")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            throw new RulesResolutionException("Invalid skill name '" + skillName //$NON-NLS-1$
                + "': skill names must be plain identifiers (e.g. 'formy', 'samoproverka'), " //$NON-NLS-1$
                + "not file paths."); //$NON-NLS-1$
        }
    }

    private static ResolvedSkill findSkillOrCommand(String name, List<LabeledRoot> roots)
    {
        for (LabeledRoot root : roots)
        {
            // 1. Standard <root>/skills/<name>/SKILL.md
            Path skillFile = root.path.resolve(SKILLS_DIR).resolve(name).resolve(SKILL_MD);
            if (Files.isRegularFile(skillFile))
            {
                String content = readUtf8(skillFile);
                if (!content.isBlank())
                {
                    return new ResolvedSkill(name, skillFile, stripYamlFrontmatter(content).trim());
                }
            }
            // 2. Direct <root>/<name>/SKILL.md (in case custom rulesSource points directly at skills/)
            Path directSkill = root.path.resolve(name).resolve(SKILL_MD);
            if (Files.isRegularFile(directSkill))
            {
                String content = readUtf8(directSkill);
                if (!content.isBlank())
                {
                    return new ResolvedSkill(name, directSkill,
                        stripYamlFrontmatter(content).trim());
                }
            }
            // 3. Command file <root>/commands/<name>.md
            Path commandFile = root.path.resolve(COMMANDS_DIR).resolve(name + ".md"); //$NON-NLS-1$
            if (Files.isRegularFile(commandFile))
            {
                String content = readUtf8(commandFile);
                if (!content.isBlank())
                {
                    return new ResolvedSkill(name, commandFile,
                        stripYamlFrontmatter(content).trim());
                }
            }
        }
        return null;
    }

    private static String buildMissingSkillMessage(String missingSkill, List<LabeledRoot> roots)
    {
        Map<String, DiscoveredEntry> available = new TreeMap<>();
        List<String> searchedPaths = new ArrayList<>();
        for (LabeledRoot root : roots)
        {
            searchedPaths.add(slashPath(root.path));
            collectSkillsInRoot(root.path, available);
            collectCommandsInRoot(root.path, available);
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Skill '").append(missingSkill).append("' was not found"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!searchedPaths.isEmpty())
        {
            msg.append(" in ").append(String.join(", ", searchedPaths)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        msg.append("."); //$NON-NLS-1$
        if (!available.isEmpty())
        {
            msg.append(" Available skills/commands: ") //$NON-NLS-1$
                .append(String.join(", ", available.keySet())) //$NON-NLS-1$
                .append("."); //$NON-NLS-1$
        }
        else
        {
            msg.append(" No skills were found in the configured .workmate directories."); //$NON-NLS-1$
        }
        return msg.toString();
    }

    private static void collectSkillsInRoot(Path root, Map<String, DiscoveredEntry> target)
    {
        Path skillsDir = Files.isDirectory(root.resolve(SKILLS_DIR))
            ? root.resolve(SKILLS_DIR)
            : root;
        if (!Files.isDirectory(skillsDir))
        {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir))
        {
            for (Path child : stream)
            {
                if (!Files.isDirectory(child))
                {
                    continue;
                }
                Path skillMd = child.resolve(SKILL_MD);
                if (Files.isRegularFile(skillMd))
                {
                    String id = child.getFileName().toString();
                    String summary = extractFrontmatterSummary(readUtf8(skillMd));
                    target.put(id, new DiscoveredEntry(id, skillMd, summary));
                }
            }
        }
        catch (IOException ignored)
        {
            // Best-effort directory listing
        }
    }

    private static void collectCommandsInRoot(Path root, Map<String, DiscoveredEntry> target)
    {
        Path commandsDir = root.resolve(COMMANDS_DIR);
        if (!Files.isDirectory(commandsDir))
        {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(commandsDir, "*.md")) //$NON-NLS-1$
        {
            for (Path child : stream)
            {
                if (Files.isRegularFile(child))
                {
                    String fileName = child.getFileName().toString();
                    String id = fileName.substring(0, fileName.length() - 3);
                    target.put(id, new DiscoveredEntry(id, child, null));
                }
            }
        }
        catch (IOException ignored)
        {
            // Best-effort directory listing
        }
    }

    static String stripYamlFrontmatter(String content)
    {
        if (content == null)
        {
            return ""; //$NON-NLS-1$
        }
        String normalized = content.startsWith("\uFEFF") ? content.substring(1) : content; //$NON-NLS-1$
        if (!normalized.startsWith("---\n") && !normalized.startsWith("---\r\n")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return normalized;
        }
        int firstNewline = normalized.indexOf('\n');
        if (firstNewline < 0)
        {
            return normalized;
        }
        int closingIdx = normalized.indexOf("\n---", firstNewline); //$NON-NLS-1$
        if (closingIdx < 0)
        {
            return normalized;
        }
        int afterClosing = normalized.indexOf('\n', closingIdx + 4);
        return afterClosing >= 0 ? normalized.substring(afterClosing + 1) : ""; //$NON-NLS-1$
    }

    private static String extractFrontmatterSummary(String content)
    {
        if (content == null)
        {
            return null;
        }
        String normalized = content.startsWith("\uFEFF") ? content.substring(1) : content; //$NON-NLS-1$
        if (!normalized.startsWith("---\n") && !normalized.startsWith("---\r\n")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        int closingIdx = normalized.indexOf("\n---", 3); //$NON-NLS-1$
        if (closingIdx < 0)
        {
            return null;
        }
        String header = normalized.substring(0, closingIdx);
        String displayName = null;
        String description = null;
        for (String line : header.split("\r?\n")) //$NON-NLS-1$
        {
            String trimmed = line.trim();
            if (trimmed.startsWith("display_name:")) //$NON-NLS-1$
            {
                displayName = unquoteYaml(trimmed.substring("display_name:".length()).trim()); //$NON-NLS-1$
            }
            else if (trimmed.startsWith("description:")) //$NON-NLS-1$
            {
                description = unquoteYaml(trimmed.substring("description:".length()).trim()); //$NON-NLS-1$
            }
        }
        if (displayName != null && !displayName.isEmpty())
        {
            return displayName;
        }
        if (description != null && !description.isEmpty())
        {
            return description;
        }
        return null;
    }

    private static String unquoteYaml(String value)
    {
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
                || (value.startsWith("'") && value.endsWith("'")))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String readUtf8(Path file)
    {
        try
        {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    private static String slashPath(Path path)
    {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static final class LabeledRoot
    {
        final String label;
        final Path path;

        LabeledRoot(String label, Path path)
        {
            this.label = label;
            this.path = path;
        }
    }

    private static final class LoadedRuleFile
    {
        final String label;
        final Path path;
        final String content;

        LoadedRuleFile(String label, Path path, String content)
        {
            this.label = label;
            this.path = path;
            this.content = content;
        }
    }

    private static final class DiscoveredEntry
    {
        final String name;
        final Path path;
        final String summary;

        DiscoveredEntry(String name, Path path, String summary)
        {
            this.name = name;
            this.path = path;
            this.summary = summary;
        }
    }

    private static final class ResolvedSkill
    {
        final String name;
        final Path path;
        final String body;

        ResolvedSkill(String name, Path path, String body)
        {
            this.name = name;
            this.path = path;
            this.body = body;
        }
    }

    /** Thrown when {@code rulesSource} or {@code skills} cannot be resolved. */
    public static final class RulesResolutionException extends Exception
    {
        private static final long serialVersionUID = 1L;

        public RulesResolutionException(String message)
        {
            super(message);
        }
    }
}
