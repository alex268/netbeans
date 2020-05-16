/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.netbeans.modules.maven.nodes;

import java.awt.Image;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JFileChooser;
import org.apache.maven.project.MavenProject;
import org.netbeans.api.annotations.common.StaticResource;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.modules.maven.NbMavenProjectImpl;
import org.netbeans.modules.maven.actions.OpenPOMAction;
import org.netbeans.modules.maven.api.FileUtilities;
import org.netbeans.modules.maven.api.NbMavenProject;
import org.netbeans.modules.maven.model.ModelOperation;
import org.netbeans.modules.maven.model.pom.POMModel;
import static org.netbeans.modules.maven.nodes.Bundle.*;
import org.netbeans.modules.maven.spi.nodes.NodeUtils;
import org.netbeans.modules.project.ui.api.ProjectActionUtils;
import org.netbeans.spi.project.ui.LogicalViewProvider;
import org.netbeans.spi.project.ui.support.CommonProjectActions;
import org.netbeans.spi.project.ui.support.ProjectChooser;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.ChildFactory;
import org.openide.nodes.Children;
import org.openide.nodes.FilterNode;
import org.openide.nodes.Node;
import org.openide.util.ContextAwareAction;
import org.openide.util.ImageUtilities;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;
import org.openide.util.RequestProcessor;
import org.openide.util.Utilities;
import org.openide.util.WeakListeners;
import org.openide.util.lookup.Lookups;

/**
 * display the modules for pom packaged project
 * @author Milos Kleint 
 */
public class ModulesNode extends AbstractNode {
	private static final @StaticResource String MODULES_BADGE = "org/netbeans/modules/maven/modules-badge.png";
    private final NbMavenProjectImpl proj;

    @Messages("LBL_Modules=Modules")
    public ModulesNode(NbMavenProjectImpl proj) {
        super(Children.create(new ModulesChildFactory(proj), true), Lookups.fixed(PathFinders.createModulesFinder()));
        this.proj = proj;
        setName("Modules"); //NOI18N
        setDisplayName(LBL_Modules());
    }

    @Override
    public Action[] getActions(boolean bool) {
        return new Action[] {
            new AddModuleAction(),
            new CreateModuleAction()
        };
    }

    private Image getIcon(boolean opened) {
        Image badge = ImageUtilities.loadImage(MODULES_BADGE, true); //NOI18N
        return ImageUtilities.mergeImages(NodeUtils.getTreeFolderIcon(opened), badge, 8, 8);
    }

    @Override
    public Image getIcon(int type) {
        return getIcon(false);
    }

    @Override
    public Image getOpenedIcon(int type) {
        return getIcon(true);
    }

    private static class ModulesChildFactory extends ChildFactory<ModulesChildFactory.SubModule> {
		private class SubModule {
			private final NbMavenProjectImpl parent;
			private final NbMavenProjectImpl proj;
			private final boolean isOpen;
			private final boolean isAggregator;
			private final LogicalViewProvider provider;
			private Node lazyNode = null;

			public SubModule(NbMavenProjectImpl parent, NbMavenProjectImpl module) {
				this.parent = parent;
				this.proj = module;
				MavenProject mp = proj.getOriginalMavenProject();
				this.isOpen =  OpenProjects.getDefault().isProjectOpen(proj);
				this.isAggregator = NbMavenProject.TYPE_POM.equals(mp.getPackaging()) && !mp.getModules().isEmpty();
				this.provider = proj.getLookup().lookup(LogicalViewProvider.class);
				assert provider != null;
			}
			
			public boolean isChanged(NbMavenProjectImpl module) {
				boolean open = OpenProjects.getDefault().isProjectOpen(proj);
				return !module.equals(proj) || open != isOpen;
			}
			
			public boolean isClosedOpenProject(Project p) {
				return proj.equals(p) && !isOpen;
			}
			
			public boolean isOpenClosedProject(Set<Project> opened) {
				return !opened.contains(proj) && isOpen;
			}

			public Node getModuleNode() {
				if (lazyNode == null) {
					Lookup lookup = isAggregator ? Lookups.fixed(PathFinders.createModulesFinder(), proj) : Lookups.fixed(PathFinders.createSubProjectFinder(), proj);
					if (isOpen) {
						lazyNode = new OpenProjectFilterNode(provider.createLogicalView(), lookup);
					} else {
						Children children = isAggregator ? Children.create(new ModulesChildFactory(proj), true) : FilterNode.Children.LEAF;
						lazyNode = new ClosedProjectFilterNode(provider.createLogicalView(), children, lookup, parent, proj);
					}
				}
				return lazyNode;
			}

			public NbMavenProjectImpl project() {
				return proj;
			}
		}

		private final NbMavenProjectImpl project;
		private final PropertyChangeListener projectListener;
		private final PropertyChangeListener modulesListener;
		private final Map<String, SubModule> modules = new HashMap<>();
		private volatile boolean modulesLoaded = false;

		ModulesChildFactory(NbMavenProjectImpl proj) {
			project = proj;
			NbMavenProject watcher = project.getProjectWatcher();
			projectListener = (PropertyChangeEvent evt) -> {
				if (NbMavenProject.PROP_PROJECT.equals(evt.getPropertyName())) {
					reloadModules();
				}
			};
			modulesListener = (PropertyChangeEvent evt) -> {
				if (!OpenProjects.PROPERTY_OPEN_PROJECTS.equals(evt.getPropertyName())) {
					return;
				}

				if (!(evt.getNewValue() instanceof Project[])) {
					return;
				}

				Project[] vv = (Project[])evt.getNewValue();
				Set<Project> vvSet = new HashSet<>();
				
				// Check if new module open
				for (Project p: vv) {
					vvSet.add(p);
					for (SubModule module: modules.values()) {
						if (module.isClosedOpenProject(p)) {
							reloadModules();
							return;
						}
					}
				}
				
				// Check if have closed module
				for (SubModule module: modules.values()) {
					if (module.isOpenClosedProject(vvSet)) {
						reloadModules();
						return;
					}
				}
			};

			OpenProjects.getDefault().addPropertyChangeListener(WeakListeners.propertyChange(modulesListener, watcher));
			watcher.addPropertyChangeListener(WeakListeners.propertyChange(projectListener, watcher));
		}
		
		public void reloadModules() {
			modulesLoaded = false;
			this.refresh(false);
		}

		private synchronized void refreshModules() {
			if (modulesLoaded) {
				return;
			}

			Set<String> modulesToRemove = new HashSet<>(modules.keySet());
			for (String module: project.getOriginalMavenProject().getModules()) {
				NbMavenProjectImpl prj = findModuleProject(module);
				if (prj != null) {
					if (modules.containsKey(module)) {
						modulesToRemove.remove(module);
						if (modules.get(module).isChanged(prj)) {
							modules.put(module, new SubModule(project, prj));
						}
					} else {
						modules.put(module, new SubModule(project, prj));
					}
				}
			}
			
			for (String module: modulesToRemove) {
				modules.remove(module);
			}
			
			modulesLoaded = true;
		}
		
		private NbMavenProjectImpl findModuleProject(String module) {
			File base = project.getOriginalMavenProject().getBasedir();
			File projDir = FileUtil.normalizeFile(new File(base, module));
			FileObject fo = FileUtil.toFileObject(projDir);
			if (fo == null) {
				//TODO broken module reference.. show as such..
				return null;
			}

			try {
				Project prj = ProjectManager.getDefault().findProject(fo);
				if(prj == null) {
					// issue #242542
					// the projects pom might be already cached by ProjectManager as NO_SUCH_PROJECT, 
					// we have to get rid of that cached value.
					// Would prefer a better place to call .clearNonProjectCache after a project was created,
					// unfortunatelly .createKeys is invoked by a chain of events triggered by the poms save document
					// - not sure how to hook before that, so that we can ensure that it isn't cached anymore.
					// - on the other hand lets not clear the ProjectManager cache on each Node refresh.
					ProjectManager.getDefault().clearNonProjectCache();
					prj = ProjectManager.getDefault().findProject(fo);
				}
				if (prj != null && prj.getLookup().lookup(NbMavenProjectImpl.class) != null) {
					return (NbMavenProjectImpl) prj;
				}
			} catch (IllegalArgumentException | IOException ex) {
				ex.printStackTrace();//TODO log ?
			}
			return null;
		}

        @Override
        protected boolean createKeys(final List<SubModule> keys) {
			refreshModules();

			for (String module : project.getOriginalMavenProject().getModules()) {
				if (modules.containsKey(module)) {
					keys.add(modules.get(module));
				}
			}
            return true;
        }

		@Override
		protected Node createNodeForKey(SubModule wr) {
			return wr.getModuleNode();
		}
    }
  
	private static class OpenProjectFilterNode extends FilterNode {
		OpenProjectFilterNode(Node node, Lookup lookup) {
			super(node, null, lookup);
		}
	}

	private static class ClosedProjectFilterNode extends FilterNode {
		private final Action openModule;
		private final Action removeModule;

		ClosedProjectFilterNode(Node node, org.openide.nodes.Children children, Lookup lookup, NbMavenProjectImpl parent, NbMavenProjectImpl module) {
			super(node, children, lookup);
//            disableDelegation(DELEGATE_GET_ACTIONS);
			openModule = new OpenProjectAction();
			removeModule = new RemoveModuleAction(parent, module);
		}

		@Override
		public Action[] getActions(boolean b) {
			ArrayList<Action> lst = new ArrayList<>();
			lst.add(openModule);
			lst.add(OpenPOMAction.instance());
			lst.add(removeModule);
//            lst.addAll(Arrays.asList(super.getActions(b)));
			return lst.toArray(new Action[lst.size()]);
		}

		@Override
		public Action getPreferredAction() {
			return openModule;
		}
	}
		

    private static class RemoveModuleAction extends AbstractAction {

        private final NbMavenProjectImpl project;
        private final NbMavenProjectImpl parent;

        @Messages("BTN_Remove_Module=Remove Module")
        RemoveModuleAction(NbMavenProjectImpl parent, NbMavenProjectImpl proj) {
            putValue(Action.NAME, BTN_Remove_Module());
            project = proj;
            this.parent = parent;
        }

        @Messages("MSG_Remove_Module=Do you want to remove the module from the parent POM?")
        @Override public void actionPerformed(ActionEvent e) {
            NotifyDescriptor nd = new NotifyDescriptor.Confirmation(MSG_Remove_Module(), NotifyDescriptor.YES_NO_OPTION);
            Object ret = DialogDisplayer.getDefault().notify(nd);
            if (ret == NotifyDescriptor.YES_OPTION) {
                FileObject fo = FileUtil.toFileObject(parent.getPOMFile());
                ModelOperation<POMModel> operation = (POMModel model) -> {
					List<String> modules = model.getProject().getModules();
					if (modules != null) {
						for (String path : modules) {
							File rel = new File(parent.getPOMFile().getParent(), path);
							File norm = FileUtil.normalizeFile(rel);
							FileObject folder = FileUtil.toFileObject(norm);
							if (folder != null && folder.equals(project.getProjectDirectory())) {
								model.getProject().removeModule(path);
								break;
							}
						}
					}
				};
                org.netbeans.modules.maven.model.Utilities.performPOMModelOperations(fo, Collections.singletonList(operation));
                //TODO is the manual reload necessary if pom.xml file is being saved?
                NbMavenProject.fireMavenProjectReload(project);
            }
        }
    }
	
    private static class OpenProjectAction extends AbstractAction implements ContextAwareAction {
        public @Override void actionPerformed(ActionEvent e) {
            assert false;
        }

        @Messages("BTN_Open_Project=Open Project")
        public @Override Action createContextAwareInstance(final Lookup context) {
            return new AbstractAction(BTN_Open_Project()) {
                public @Override void actionPerformed(ActionEvent e) {
                    Collection<? extends NbMavenProjectImpl> projects = context.lookupAll(NbMavenProjectImpl.class);
                    final NbMavenProjectImpl[] projectsArray = projects.toArray(new NbMavenProjectImpl[0]);
                    if(projectsArray.length > 0) {
                        RequestProcessor.getDefault().post(() -> {
							OpenProjects.getDefault().open(projectsArray, false, false);
							RequestProcessor.getDefault().post(() -> {
								ProjectActionUtils.selectAndExpandProject(projectsArray[0]);
							}, 500);
						});
                    }
                }
            };
        }
    }

    private class AddModuleAction extends AbstractAction {

        @Messages("BTN_add_module=Add Existing Module...")
        AddModuleAction() {
            super(BTN_add_module());
        }

        @Override public void actionPerformed(ActionEvent e) {
            JFileChooser c = ProjectChooser.projectChooser();
            File basedir = FileUtil.toFile(proj.getProjectDirectory());
            c.setCurrentDirectory(basedir);
            if (c.showOpenDialog(Utilities.findDialogParent()) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            final List<String> mods = new ArrayList<>();
            for (File d : c.getSelectedFiles()) {
                String mod = FileUtilities.relativizeFile(basedir, d);
                if (mod != null && !mod.equals(".")) {
                    mods.add(mod);
                }
            }
            if (mods.isEmpty()) {
                return;
            }
            org.netbeans.modules.maven.model.Utilities.performPOMModelOperations(proj.getProjectDirectory().getFileObject("pom.xml"), Collections.singletonList(new ModelOperation<POMModel>() {
                @Override public void performOperation(POMModel model) {
                    for (String mod : mods) {
                        model.getProject().addModule(mod);
                    }
                }
            }));
        }

    }
    
    private class CreateModuleAction extends AbstractAction {

        @Messages("BTN_create_module=Create New Module...")
        CreateModuleAction() {
            super(BTN_create_module());
        }

        @Override public void actionPerformed(ActionEvent e) {
            Action act = CommonProjectActions.newProjectAction();
            act.putValue("PRESELECT_CATEGORY" /*ProjectTemplates.PRESELECT_CATEGORY */, "Maven2");
            act.putValue(CommonProjectActions.PROJECT_PARENT_FOLDER, proj.getPOMFile().getParentFile());
            act.putValue("initialValueProperties", new String[] {"groupId", "version"});
            act.putValue("groupId", proj.getOriginalMavenProject().getGroupId());
            act.putValue("version", proj.getOriginalMavenProject().getVersion());
            act.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "actionPerformed"));
        }

    }

}
