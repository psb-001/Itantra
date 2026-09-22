Team HexxCode - Smart India Hackathon Winner
Problem Statement - SIH1465
Ministry - AICTE
College of Engineering Pune, Technological University (COEP Tech)
Demo
·
Presentation
Table of Contents
About The Project
Built With
Getting Started
Prerequisites
Installation
Structure Hierarchy
Usage
Features
Contributors
## About The Project

HexxCode's Shiksha Niyojak is a unified portal for developing a model curriculum for all the AICTE Approved institutes. This portal connects all the educational stakeholders and aids them in designing a standardized model curriculum for all the approved institutes. 

Here's a brief description and role divisions of all the stakeholders:
*
AICTE Admin :
Approves Curriculum Developers, Releases Educational Guidelines, Appoints Bureau Heads, Approves Curriculums and Redirects them to Educational Experts 
*
Bureau Heads :
Makes Subject wise groups from approved list of curriculum developers, Reviews and merges all curriculums and sends them to AICTE Admins
*
Curriculum Developers :
Develops curriculum for the allocated subject by satisfying following 5 parameters : Learning Outcomes, Modules, Assessment Details, Resources, Suggested Videos
*
Educational Experts :
Reviews curriculums for different departments according to these
25 parameters »
### Built With
## Getting Started

Following are the instructions to set-up Shiksha Niyojak and run it locally.

### Prerequisites

Installations required:
```sh
    npm install npm@latest -g
```
MySQL Workbench
MongoDB Compass
### Installation

1. Create a new
AWS RDS (Relational Database System)
2. Create a
MongoDB Database
3. Git clone the repo
```sh
    git clone https://github.com/pt3002/HexxCode-SIH-2023
```
4. Add .env file in the following format in the root folder: 
```sh
    MONGO_DB = "mongodb+srv://xxx:yyy@cluster0.fznky7d.mongodb.net/SIH?retryWrites=true&w=majority"
    PORT = 5001
    DB_PORT = 3306
    HOST = "xxx.yyy.ap-south-1.rds.amazonaws.com"
    PASSWORD = "xxx"
    DATABASE = "xxx"
    JWT_SECRET = "xxx"
    CRYPTO_KEY = "xxx"
```
5. Install node modules in root and client folder:
 ```sh 
    npm i
```
6. Execute the Project in root folder:
```sh
    npm run dev
```
## Usage
Here is a Video depicting the role wise distribution of various stakeholders in developing a model curriculum.

https://github.com/pt3002/HexxCode-SIH-2023/assets/87142731/7906c22d-8b69-4597-a603-535f0861450b

## Structure Hierarchy
![Structure_Hierarchy](https://github.com/pt3002/HexxCode-SIH-2023/assets/87142731/9b47a37c-5ee1-4805-9fd2-c6632c4c5886)


## Features

*
Personalized Dashboard:
For different Educational Stakeholders.
*
Calendar:
For setting up meetings and connecting with curriculum developers.
*
Curriculum Development Tool:
A collaborative tool with version control and edit access according to the group assigned to the curriculum developer.
*
Discussion Forum:
A forum for anonymous discussions with live tracking of likes, upvotes, reviews.
*
Latest Guidelines:
Mechanism through which AICTE Admin adds Indian Education Guidelines which have to be followed by different stakeholders.
*
Grouping Developers:
Right given to bureau heads to make subject wise groups and adding approved Curriculum Developers to this group.
*
LTP Model:
Lectures, Tutorials and Practicals which can be assigned for different departments.
*
Collecting Expert Requirements:
Mechanism of reviewing and rating curriculum designs approved by AICTE Admins.
*
Visual Analytics:
To get a deep understanding of the feedback received for a particular curriculum design.

## Contributors
Prerna Tulsiani
LinkedIn
Github
Tanvi Kale
LinkedIn
Github
Jia Johnson
LinkedIn
Github
Harshal Kausadikar
LinkedIn
Github
Pratik Patil
LinkedIn
Github
Abhinav Kurule
LinkedIn
Github