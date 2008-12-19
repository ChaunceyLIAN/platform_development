/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ide.eclipse.adt.project;

import com.android.ide.eclipse.adt.AdtPlugin;
import com.android.ide.eclipse.common.AndroidConstants;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Action going through all the source of a project and creating a pre-processed aidl file
 * with all the custom parcelable classes.
 */
public class CreateAidlImportAction  implements IObjectActionDelegate {

    private ISelection mSelection;

    public CreateAidlImportAction() {
        // pass
    }

    public void setActivePart(IAction action, IWorkbenchPart targetPart) {
        // pass
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.ui.IActionDelegate#run(org.eclipse.jface.action.IAction)
     */
    public void run(IAction action) {
        if (mSelection instanceof IStructuredSelection) {
            for (Iterator<?> it = ((IStructuredSelection)mSelection).iterator(); it.hasNext();) {
                Object element = it.next();
                IProject project = null;
                if (element instanceof IProject) {
                    project = (IProject)element;
                } else if (element instanceof IAdaptable) {
                    project = (IProject)((IAdaptable)element).getAdapter(IProject.class);
                }
                if (project != null) {
                    final IProject fproject = project;
                    new Job("Aidl preprocess") {
                        @Override
                        protected IStatus run(IProgressMonitor monitor) {
                            return createImportFile(fproject, monitor);
                        }
                    }.schedule();
                }
            }
        }
    }

    public void selectionChanged(IAction action, ISelection selection) {
        mSelection = selection;
    }

    private IStatus createImportFile(IProject project, IProgressMonitor monitor) {
        try {
            if (monitor != null) {
                monitor.beginTask(String.format(
                        "Creating aid preprocess file for %1$s", project.getName()), 1);
            }
            
            ArrayList<String> parcelables = new ArrayList<String>();
            
            IJavaProject javaProject = JavaCore.create(project);
            
            IPackageFragmentRoot[] roots = javaProject.getPackageFragmentRoots();
            
            for (IPackageFragmentRoot root : roots) {
                if (root.isArchive() == false && root.isExternal() == false) {
                    parsePackageFragmentRoot(root, parcelables, monitor);
                }
            }
            
            // create the file with the parcelables
            if (parcelables.size() > 0) {
                IPath path = project.getLocation();
                path = path.append(AndroidConstants.FN_PROJECT_AIDL);
                
                File f = new File(path.toOSString());
                if (f.exists() == false) {
                    if (f.createNewFile() == false) {
                        return new Status(IStatus.ERROR, AdtPlugin.PLUGIN_ID,
                                "Failed to create /project.aidl");
                    }
                }
                
                FileWriter fw = new FileWriter(f);
                
                fw.write("// This file is auto-generated by the\n");
                fw.write("//    'Create Aidl preprocess file for Parcelable classes'\n");
                fw.write("// action. Do not modify!\n\n");
                
                for (String parcelable : parcelables) {
                    fw.write("parcelable "); //$NON-NLS-1$
                    fw.write(parcelable);
                    fw.append(";\n"); //$NON-NLS-1$
                }
                
                fw.close();
                
                // need to refresh the level just below the project to make sure it's being picked
                // up by eclipse.
                project.refreshLocal(IResource.DEPTH_ONE, monitor);
            }
            
            if (monitor != null) {
                monitor.worked(1);
                monitor.done();
            }
            
            return Status.OK_STATUS;
        } catch (JavaModelException e) {
            return e.getJavaModelStatus();
        } catch (IOException e) {
            return new Status(IStatus.ERROR, AdtPlugin.PLUGIN_ID,
                    "Failed to create /project.aidl", e);
        } catch (CoreException e) {
            return e.getStatus();
        } finally {
            if (monitor != null) {
                monitor.done();
            }
        }
    }
    
    private void parsePackageFragmentRoot(IPackageFragmentRoot root,
            ArrayList<String> parcelables, IProgressMonitor monitor) throws JavaModelException {
        
        IJavaElement[] elements = root.getChildren();
        
        for (IJavaElement element : elements) {
            if (element instanceof IPackageFragment) {
                ICompilationUnit[] compilationUnits =
                    ((IPackageFragment)element).getCompilationUnits();
                
                for (ICompilationUnit unit : compilationUnits) {
                    IType[] types = unit.getTypes();
                    
                    for (IType type : types) {
                        parseType(type, parcelables, monitor);
                    }
                }
            }
        }
    }

    private void parseType(IType type, ArrayList<String> parcelables, IProgressMonitor monitor)
            throws JavaModelException {
        // first look in this type if it somehow extends parcelable.
        ITypeHierarchy typeHierarchy = type.newSupertypeHierarchy(monitor);
        
        IType[] superInterfaces = typeHierarchy.getAllSuperInterfaces(type);
        for (IType superInterface : superInterfaces) {
            if (AndroidConstants.CLASS_PARCELABLE.equals(superInterface.getFullyQualifiedName())) {
                parcelables.add(type.getFullyQualifiedName());
            }
        }
        
        // then look in inner types.
        IType[] innerTypes = type.getTypes();
        
        for (IType innerType : innerTypes) {
            parseType(innerType, parcelables, monitor);
        }
    }
}
